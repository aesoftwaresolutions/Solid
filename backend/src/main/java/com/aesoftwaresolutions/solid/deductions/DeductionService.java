package com.aesoftwaresolutions.solid.deductions;

import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Mileage log and home-office declaration: tax evidence, not journal entries (see spec 015). */
@Service
public class DeductionService {

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final DeductionRates rates;

    DeductionService(JdbcClient db, OrgScope orgScope, OrgService orgs, DeductionRates rates) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.rates = rates;
    }

    // ---------------- vehicles ----------------

    public DeductionModels.Vehicle createVehicle(UUID orgId, UUID entityId, String name, String description,
                                                 LocalDate inServiceDate) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> {
            UUID id = Ids.newId();
            db.sql("insert into pf.vehicle (id, org_id, entity_id, name, description, in_service_date) values (?, ?, ?, ?, ?, ?)")
                    .params(id, orgId, entityId, name.trim(), description, inServiceDate).update();
            return findVehicle(entityId, id);
        });
    }

    public List<DeductionModels.Vehicle> listVehicles(UUID orgId, UUID entityId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(VEHICLE_SELECT + " where entity_id = ? order by name")
                .param(entityId).query(DeductionModels.Vehicle.class).list());
    }

    // ---------------- trips ----------------

    public DeductionModels.Trip addTrip(UUID orgId, UUID entityId, UUID vehicleId, LocalDate tripDate, BigDecimal miles,
                                        String category, String purpose, String startLocation, String endLocation) {
        orgs.getEntity(orgId, entityId);
        if (miles == null || miles.signum() <= 0 || miles.scale() > 1 || miles.compareTo(new BigDecimal("10000")) > 0) {
            throw new IllegalArgumentException("Miles must be greater than 0, at most 10000, with one decimal place");
        }
        if ("business".equals(category) && (purpose == null || purpose.isBlank())) {
            throw new BusinessRuleException("PURPOSE_REQUIRED",
                    "Business trips need a purpose — the IRS expects a record of why you drove");
        }
        return orgScope.call(orgId, () -> {
            findVehicle(entityId, vehicleId);
            UUID id = Ids.newId();
            db.sql("""
                    insert into pf.mileage_trip (id, org_id, entity_id, vehicle_id, trip_date, miles, category, purpose,
                                                 start_location, end_location)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, entityId, vehicleId, tripDate, miles, category,
                            purpose == null ? null : purpose.trim(), startLocation, endLocation)
                    .update();
            return findTrip(entityId, id);
        });
    }

    public List<DeductionModels.Trip> listTrips(UUID orgId, UUID entityId, Integer taxYear, String category) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> db.sql(TRIP_SELECT + """
                 where entity_id = :entity
                   and (cast(:year as int) is null or extract(year from trip_date) = cast(:year as int))
                   and (cast(:category as text) is null or category = cast(:category as text))
                 order by trip_date, created_at""")
                .param("entity", entityId).param("year", taxYear).param("category", category)
                .query(DeductionModels.Trip.class).list());
    }

    public void deleteTrip(UUID orgId, UUID entityId, UUID tripId) {
        orgs.getEntity(orgId, entityId);
        orgScope.run(orgId, () -> {
            int deleted = db.sql("delete from pf.mileage_trip where entity_id = ? and id = ?")
                    .params(entityId, tripId).update();
            if (deleted == 0) {
                throw new NotFoundException("Trip " + tripId + " not found");
            }
        });
    }

    public DeductionModels.MileageReport mileageReport(UUID orgId, UUID entityId, int taxYear) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        Optional<BigDecimal> rate = rates.mileageRate(taxYear);

        return orgScope.call(orgId, () -> {
            Map<String, BigDecimal> byCategory = new java.util.HashMap<>();
            for (Map<String, Object> row : db.sql("""
                    select category, sum(miles) as miles from pf.mileage_trip
                    where entity_id = ? and extract(year from trip_date) = ?
                    group by category""").params(entityId, taxYear).query().listOfRows()) {
                byCategory.put((String) row.get("category"), (BigDecimal) row.get("miles"));
            }
            BigDecimal business = byCategory.getOrDefault("business", BigDecimal.ZERO);
            BigDecimal commuting = byCategory.getOrDefault("commuting", BigDecimal.ZERO);
            BigDecimal personal = byCategory.getOrDefault("personal", BigDecimal.ZERO);
            BigDecimal other = byCategory.getOrDefault("charity", BigDecimal.ZERO)
                    .add(byCategory.getOrDefault("medical", BigDecimal.ZERO));

            List<DeductionModels.VehicleMiles> byVehicle = db.sql("""
                    select v.id as vehicle_id, v.name,
                           coalesce(sum(t.miles) filter (where t.category = 'business'), 0) as business_miles,
                           coalesce(sum(t.miles), 0) as total_miles
                    from pf.vehicle v
                    left join pf.mileage_trip t on t.vehicle_id = v.id and extract(year from t.trip_date) = :year
                    where v.entity_id = :entity
                    group by v.id, v.name order by v.name""")
                    .param("entity", entityId).param("year", taxYear)
                    .query((rs, n) -> new DeductionModels.VehicleMiles(rs.getObject("vehicle_id", UUID.class),
                            rs.getString("name"), rs.getBigDecimal("business_miles"), rs.getBigDecimal("total_miles")))
                    .list();

            Money deduction = rate.map(r -> Money.of(business.multiply(r), ccy, RoundingMode.HALF_UP)).orElse(null);
            String note = rate.isPresent()
                    ? "Estimated deduction = business miles x the IRS standard mileage rate for " + taxYear
                            + ". Keep the log: the IRS expects contemporaneous records."
                    : "No IRS standard mileage rate for " + taxYear + " is on file, so Solid reports miles only and "
                            + "will not guess a rate. An administrator can add it on the installation page once the IRS "
                            + "announces it, or it arrives with a later version of Solid.";
            return new DeductionModels.MileageReport(taxYear, rate.isPresent(), rate.orElse(null), business, commuting,
                    personal, other, deduction, rates.mileageSource(taxYear), note, byVehicle);
        });
    }

    // ---------------- home office ----------------

    public DeductionModels.HomeOffice saveHomeOffice(UUID orgId, UUID entityId, int taxYear, String method,
                                                     int totalHomeSquareFeet, int officeSquareFeet, Integer monthsUsed) {
        orgs.getEntity(orgId, entityId);
        if (!"simplified".equals(method)) {
            throw new IllegalArgumentException("Only the simplified method is available. The actual-expense method "
                    + "(Form 8829) needs the tax rule pack, which isn't built yet.");
        }
        if (officeSquareFeet <= 0 || totalHomeSquareFeet <= 0 || officeSquareFeet > totalHomeSquareFeet) {
            throw new IllegalArgumentException("Office square feet must be positive and no larger than the home");
        }
        if (monthsUsed != null && (monthsUsed < 1 || monthsUsed > 12)) {
            throw new IllegalArgumentException("Months used must be between 1 and 12");
        }
        return orgScope.call(orgId, () -> {
            db.sql("""
                    insert into pf.home_office (entity_id, tax_year, org_id, method, total_home_square_feet,
                                                office_square_feet, months_used)
                    values (?, ?, ?, ?, ?, ?, ?)
                    on conflict (entity_id, tax_year) do update set method = excluded.method,
                        total_home_square_feet = excluded.total_home_square_feet,
                        office_square_feet = excluded.office_square_feet,
                        months_used = excluded.months_used, updated_at = now()""")
                    .params(entityId, taxYear, orgId, method, totalHomeSquareFeet, officeSquareFeet, monthsUsed)
                    .update();
            return findHomeOffice(entityId, taxYear).orElseThrow();
        });
    }

    public Optional<DeductionModels.HomeOffice> getHomeOffice(UUID orgId, UUID entityId, int taxYear) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> findHomeOffice(entityId, taxYear));
    }

    public Optional<DeductionModels.HomeOfficeReport> homeOfficeReport(UUID orgId, UUID entityId, int taxYear) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String ccy = entity.baseCurrency();
        DeductionRates.HomeOfficeSimplified simplified = rates.homeOfficeSimplified(taxYear);

        return orgScope.call(orgId, () -> findHomeOffice(entityId, taxYear).map(declaration -> {
            int counted = Math.min(declaration.officeSquareFeet(), simplified.maximumSquareFeet());
            BigDecimal full = simplified.ratePerSquareFoot().multiply(BigDecimal.valueOf(counted));
            BigDecimal prorated = declaration.monthsUsed() == null ? full
                    : full.multiply(BigDecimal.valueOf(declaration.monthsUsed()))
                    .divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
            BigDecimal businessUse = BigDecimal.valueOf(declaration.officeSquareFeet())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(declaration.totalHomeSquareFeet()), 2, RoundingMode.HALF_UP);
            String note = "Simplified method: " + counted + " sq ft counted (cap " + simplified.maximumSquareFeet()
                    + ") at " + simplified.ratePerSquareFoot() + " per sq ft"
                    + (declaration.monthsUsed() == null ? "" : ", prorated for " + declaration.monthsUsed() + " months")
                    + ". The deduction is limited to the business's gross income — your preparer applies that limit.";
            return new DeductionModels.HomeOfficeReport(taxYear, declaration.method(), declaration.officeSquareFeet(),
                    counted, simplified.maximumSquareFeet(), simplified.ratePerSquareFoot(), businessUse,
                    declaration.monthsUsed(), Money.of(prorated, ccy, RoundingMode.HALF_UP), simplified.source(), note);
        }));
    }

    // ---------------- internals ----------------

    private DeductionModels.Vehicle findVehicle(UUID entityId, UUID vehicleId) {
        return db.sql(VEHICLE_SELECT + " where entity_id = ? and id = ?").params(entityId, vehicleId)
                .query(DeductionModels.Vehicle.class).optional()
                .orElseThrow(() -> new NotFoundException("Vehicle " + vehicleId + " not found"));
    }

    private DeductionModels.Trip findTrip(UUID entityId, UUID tripId) {
        return db.sql(TRIP_SELECT + " where entity_id = ? and id = ?").params(entityId, tripId)
                .query(DeductionModels.Trip.class).optional()
                .orElseThrow(() -> new NotFoundException("Trip " + tripId + " not found"));
    }

    private Optional<DeductionModels.HomeOffice> findHomeOffice(UUID entityId, int taxYear) {
        return db.sql("""
                select entity_id, tax_year, method, total_home_square_feet, office_square_feet, months_used
                from pf.home_office where entity_id = ? and tax_year = ?""")
                .params(entityId, taxYear).query(DeductionModels.HomeOffice.class).optional();
    }

    private static final String VEHICLE_SELECT = """
            select id, entity_id, name, description, in_service_date, is_archived from pf.vehicle""";

    private static final String TRIP_SELECT = """
            select id, entity_id, vehicle_id, trip_date, miles, category, purpose, start_location, end_location
            from pf.mileage_trip""";
}
