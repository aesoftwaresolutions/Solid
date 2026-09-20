import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { ApiError, api, formatMoney, type Vehicle } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const CATEGORIES = ['business', 'commuting', 'personal', 'charity', 'medical'];
const today = () => new Date().toISOString().slice(0, 10);

export default function DeductionsPage() {
  const { orgId = '', entityId = '' } = useParams();
  const [taxYear, setTaxYear] = useState(new Date().getFullYear());

  const vehicles = useLoader(() => api.vehicles(orgId, entityId), [orgId, entityId]);
  const trips = useLoader(() => api.trips(orgId, entityId, taxYear), [orgId, entityId, taxYear]);
  const mileage = useLoader(() => api.mileageReport(orgId, entityId, taxYear), [orgId, entityId, taxYear]);
  const homeOffice = useLoader(
    () => api.homeOfficeReport(orgId, entityId, taxYear).catch((e) => {
      // No declaration for that year is a normal state, not an error.
      if (e instanceof ApiError && e.status === 404) {
        return undefined;
      }
      throw e;
    }),
    [orgId, entityId, taxYear],
  );

  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  const vehicleName = (id: string) => vehicles.value?.find((v) => v.id === id)?.name ?? id;

  return (
    <main>
      <h1>Deductions</h1>
      <ErrorMessage error={vehicles.error} />
      <ErrorMessage error={trips.error} />
      <ErrorMessage error={error} />

      <Card title="Tax year">
        <label>
          Year
          <input
            type="number"
            value={taxYear}
            min={2000}
            max={2100}
            onChange={(e) => setTaxYear(Number(e.target.value))}
          />
        </label>
      </Card>

      <Card title="Vehicles">
        <NewVehicle orgId={orgId} entityId={entityId} onCreated={vehicles.reload} />
        {!vehicles.value && !vehicles.error && <Loading what="vehicles" />}
        <ul>
          {(vehicles.value ?? []).map((vehicle) => (
            <li key={vehicle.id}>{vehicle.name}</li>
          ))}
        </ul>
      </Card>

      <Card title="Mileage log">
        {vehicles.value && vehicles.value.length === 0 ? (
          <p className="muted">Add a vehicle first.</p>
        ) : (
          vehicles.value && (
            <NewTrip
              orgId={orgId}
              entityId={entityId}
              vehicles={vehicles.value}
              onAdded={() => {
                trips.reload();
                mileage.reload();
              }}
            />
          )
        )}
        {trips.value && trips.value.length === 0 && <p className="muted">No trips logged for {taxYear}.</p>}
        {trips.value && trips.value.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Vehicle</th>
                <th className="money">Miles</th>
                <th>Category</th>
                <th>Purpose</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {trips.value.map((trip) => (
                <tr key={trip.id}>
                  <td>{trip.tripDate}</td>
                  <td>{vehicleName(trip.vehicleId)}</td>
                  <td className="money">{trip.miles}</td>
                  <td>{trip.category}</td>
                  <td>{trip.purpose ?? ''}</td>
                  <td>
                    <button
                      type="button"
                      className="secondary"
                      disabled={busy}
                      onClick={() => {
                        setBusy(true);
                        setError(undefined);
                        api
                          .deleteTrip(orgId, entityId, trip.id)
                          .then(() => {
                            trips.reload();
                            mileage.reload();
                          })
                          .catch(setError)
                          .finally(() => setBusy(false));
                      }}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title={`Mileage ${taxYear}`}>
        <ErrorMessage error={mileage.error} />
        {mileage.value && (
          <>
            <div className="stats">
              <div className="stat">
                <div className="muted">Business miles</div>
                <div className="value">{mileage.value.businessMiles}</div>
              </div>
              <div className="stat">
                <div className="muted">Commuting</div>
                <div className="value">{mileage.value.commutingMiles}</div>
              </div>
              <div className="stat">
                <div className="muted">Personal</div>
                <div className="value">{mileage.value.personalMiles}</div>
              </div>
              {mileage.value.rateKnown && mileage.value.estimatedDeduction && (
                <div className="stat">
                  <div className="muted">Estimated deduction</div>
                  <div className="value">{formatMoney(mileage.value.estimatedDeduction)}</div>
                </div>
              )}
            </div>
            {mileage.value.rateKnown ? (
              <p className="muted">
                At {mileage.value.ratePerMile} per mile. {mileage.value.source}
              </p>
            ) : (
              <p className="notice">
                The standard mileage rate for {taxYear} is not on file, so no deduction is shown — the miles above
                are still recorded.
              </p>
            )}
            <p className="muted">{mileage.value.note}</p>
          </>
        )}
      </Card>

      <Card title={`Home office ${taxYear}`}>
        <ErrorMessage error={homeOffice.error} />
        <HomeOfficeForm
          orgId={orgId}
          entityId={entityId}
          taxYear={taxYear}
          onSaved={() => homeOffice.reload()}
        />
        {homeOffice.value ? (
          <>
            <div className="stats">
              <div className="stat">
                <div className="muted">Counted square feet</div>
                <div className="value">{homeOffice.value.countedSquareFeet}</div>
              </div>
              {homeOffice.value.deduction && (
                <div className="stat">
                  <div className="muted">Deduction</div>
                  <div className="value">{formatMoney(homeOffice.value.deduction)}</div>
                </div>
              )}
            </div>
            {homeOffice.value.source && <p className="muted">{homeOffice.value.source}</p>}
            <p className="muted">{homeOffice.value.note}</p>
          </>
        ) : (
          <p className="muted">No home office declared for {taxYear} yet.</p>
        )}
      </Card>
    </main>
  );
}

function NewVehicle({ orgId, entityId, onCreated }: { orgId: string; entityId: string; onCreated: () => void }) {
  const [name, setName] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .createVehicle(orgId, entityId, name.trim())
          .then(() => {
            setName('');
            onCreated();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Vehicle name
        <input value={name} required maxLength={120} onChange={(e) => setName(e.target.value)} />
      </label>
      <button type="submit" disabled={busy}>
        Add vehicle
      </button>
    </form>
  );
}

function NewTrip({
  orgId,
  entityId,
  vehicles,
  onAdded,
}: {
  orgId: string;
  entityId: string;
  vehicles: Vehicle[];
  onAdded: () => void;
}) {
  const [vehicleId, setVehicleId] = useState(vehicles[0]?.id ?? '');
  const [tripDate, setTripDate] = useState(today());
  const [miles, setMiles] = useState('');
  const [category, setCategory] = useState('business');
  const [purpose, setPurpose] = useState('');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .addTrip(orgId, entityId, {
            vehicleId,
            tripDate,
            miles: miles.trim(),
            category,
            purpose: purpose.trim() || undefined,
          })
          .then(() => {
            setMiles('');
            setPurpose('');
            onAdded();
          })
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Vehicle
        <select value={vehicleId} onChange={(e) => setVehicleId(e.target.value)}>
          {vehicles.map((vehicle) => (
            <option key={vehicle.id} value={vehicle.id}>
              {vehicle.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Date
        <input type="date" value={tripDate} required onChange={(e) => setTripDate(e.target.value)} />
      </label>
      <label>
        Miles
        <input value={miles} required inputMode="decimal" onChange={(e) => setMiles(e.target.value)} />
      </label>
      <label>
        Category
        <select value={category} onChange={(e) => setCategory(e.target.value)}>
          {CATEGORIES.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
      </label>
      <label>
        Purpose
        <input value={purpose} maxLength={300} onChange={(e) => setPurpose(e.target.value)} />
      </label>
      <button type="submit" disabled={busy}>
        Log trip
      </button>
    </form>
  );
}

function HomeOfficeForm({
  orgId,
  entityId,
  taxYear,
  onSaved,
}: {
  orgId: string;
  entityId: string;
  taxYear: number;
  onSaved: () => void;
}) {
  const [totalHomeSquareFeet, setTotalHomeSquareFeet] = useState('');
  const [officeSquareFeet, setOfficeSquareFeet] = useState('');
  const [monthsUsed, setMonthsUsed] = useState('12');
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(undefined);
        api
          .saveHomeOffice(orgId, entityId, taxYear, {
            method: 'simplified',
            totalHomeSquareFeet: Number(totalHomeSquareFeet),
            officeSquareFeet: Number(officeSquareFeet),
            monthsUsed: Number(monthsUsed),
          })
          .then(onSaved)
          .catch(setError)
          .finally(() => setBusy(false));
      }}
    >
      <ErrorMessage error={error} />
      <label>
        Home square feet
        <input
          value={totalHomeSquareFeet}
          required
          inputMode="numeric"
          onChange={(e) => setTotalHomeSquareFeet(e.target.value)}
        />
      </label>
      <label>
        Office square feet
        <input
          value={officeSquareFeet}
          required
          inputMode="numeric"
          onChange={(e) => setOfficeSquareFeet(e.target.value)}
        />
      </label>
      <label>
        Months used
        <input value={monthsUsed} required inputMode="numeric" onChange={(e) => setMonthsUsed(e.target.value)} />
      </label>
      <button type="submit" disabled={busy}>
        Save declaration
      </button>
    </form>
  );
}
