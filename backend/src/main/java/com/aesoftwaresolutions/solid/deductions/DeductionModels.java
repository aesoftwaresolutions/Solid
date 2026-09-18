package com.aesoftwaresolutions.solid.deductions;

import com.aesoftwaresolutions.solid.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class DeductionModels {

    private DeductionModels() {
    }

    public record Vehicle(UUID id, UUID entityId, String name, String description, LocalDate inServiceDate,
                          boolean isArchived) {
    }

    public record Trip(UUID id, UUID entityId, UUID vehicleId, LocalDate tripDate, BigDecimal miles, String category,
                       String purpose, String startLocation, String endLocation) {
    }

    public record VehicleMiles(UUID vehicleId, String vehicleName, BigDecimal businessMiles, BigDecimal totalMiles) {
    }

    public record MileageReport(int taxYear, boolean rateKnown, BigDecimal ratePerMile, BigDecimal businessMiles,
                                BigDecimal commutingMiles, BigDecimal personalMiles, BigDecimal otherMiles,
                                Money estimatedDeduction, String source, String note, List<VehicleMiles> byVehicle) {
    }

    public record HomeOffice(UUID entityId, int taxYear, String method, int totalHomeSquareFeet, int officeSquareFeet,
                             Integer monthsUsed) {
    }

    public record HomeOfficeReport(int taxYear, String method, int officeSquareFeet, int countedSquareFeet,
                                   int maximumSquareFeet, BigDecimal ratePerSquareFoot, BigDecimal businessUsePercent,
                                   Integer monthsUsed, Money deduction, String source, String note) {
    }
}
