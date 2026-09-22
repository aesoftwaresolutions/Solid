import { useEffect, useState, type FormEvent } from 'react';
import { useParams } from 'react-router-dom';
import { api, type Branding } from '../api';
import { Card, ErrorMessage, Loading, useLoader } from '../components';

const STATES = [
  'AL', 'AK', 'AZ', 'AR', 'CA', 'CO', 'CT', 'DE', 'DC', 'FL', 'GA', 'HI', 'ID', 'IL', 'IN', 'IA', 'KS', 'KY',
  'LA', 'ME', 'MD', 'MA', 'MI', 'MN', 'MS', 'MO', 'MT', 'NE', 'NV', 'NH', 'NJ', 'NM', 'NY', 'NC', 'ND', 'OH',
  'OK', 'OR', 'PA', 'RI', 'SC', 'SD', 'TN', 'TX', 'UT', 'VT', 'VA', 'WA', 'WV', 'WI', 'WY',
];

export default function EntitySettingsPage() {
  const { orgId = '', entityId = '' } = useParams();
  const entity = useLoader(() => api.entity(orgId, entityId), [orgId, entityId]);
  const integrity = useLoader(() => api.verifyJournal(orgId, entityId), [orgId, entityId]);

  const [legalName, setLegalName] = useState('');
  const [homeState, setHomeState] = useState('');
  const [accountingMethod, setAccountingMethod] = useState('cash');
  const [saved, setSaved] = useState(false);
  const branding = useLoader(() => api.branding(orgId, entityId), [orgId, entityId]);
  const [letterhead, setLetterhead] = useState<Omit<Branding, 'hasLogo'>>({
    address: '',
    phone: '',
    email: '',
    website: '',
    taxId: '',
    paymentInstructions: '',
  });
  const [letterheadSaved, setLetterheadSaved] = useState(false);
  const [letterheadError, setLetterheadError] = useState<unknown>(undefined);
  const [error, setError] = useState<unknown>(undefined);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (entity.value) {
      setLegalName(entity.value.legalName);
      setHomeState(entity.value.homeState ?? '');
      setAccountingMethod(entity.value.accountingMethod);
    }
  }, [entity.value]);

  useEffect(() => {
    if (branding.value) {
      const { hasLogo: _hasLogo, ...fields } = branding.value;
      setLetterhead({
        address: fields.address ?? '',
        phone: fields.phone ?? '',
        email: fields.email ?? '',
        website: fields.website ?? '',
        taxId: fields.taxId ?? '',
        paymentInstructions: fields.paymentInstructions ?? '',
      });
    }
  }, [branding.value]);

  const field = (key: keyof Omit<Branding, 'hasLogo'>) => ({
    value: letterhead[key] ?? '',
    onChange: (e: { target: { value: string } }) =>
      setLetterhead((current) => ({ ...current, [key]: e.target.value })),
  });

  const saveLetterhead = (event: FormEvent) => {
    event.preventDefault();
    setLetterheadError(undefined);
    setLetterheadSaved(false);
    api
      .saveBranding(orgId, entityId, letterhead)
      .then((updated) => {
        branding.setValue(updated);
        setLetterheadSaved(true);
      })
      .catch(setLetterheadError);
  };

  const chooseLogo = (file: File | undefined) => {
    if (!file) {
      return;
    }
    setLetterheadError(undefined);
    api.uploadLogo(orgId, entityId, file).then(branding.setValue).catch(setLetterheadError);
  };

  const save = (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    setSaved(false);
    api
      .updateEntity(orgId, entityId, {
        legalName: legalName.trim(),
        accountingMethod,
        homeState: homeState || undefined,
      })
      .then((updated) => {
        entity.setValue(updated);
        setSaved(true);
      })
      .catch(setError)
      .finally(() => setBusy(false));
  };

  return (
    <main>
      <h1>Entity settings</h1>
      <ErrorMessage error={entity.error} />
      {!entity.value && !entity.error && <Loading what="this entity" />}

      <Card title="Are the books intact?">
        <ErrorMessage error={integrity.error} />
        {!integrity.value && !integrity.error && <Loading what="the integrity check" />}
        {integrity.value && (
          <p role="status">
            {integrity.value.valid
              ? `Every one of the ${integrity.value.postedEntries} posted entries still hashes to the one before it,
                 so nothing has been altered or removed since it was posted.`
              : `The chain breaks at entry ${integrity.value.firstInvalidSeq}. Something changed the posted
                 entries outside Solid — restore from a backup and tell whoever administers this server.`}
          </p>
        )}
      </Card>

      {entity.value && (
        <Card title="How this entity keeps its books">
          <p className="muted">
            The kind of entity ({entity.value.kind}), its base currency ({entity.value.baseCurrency}) and its
            fiscal year end (month {entity.value.fiscalYearEnd}) are fixed here: changing any of them would
            change what entries already posted mean.
          </p>
          <ErrorMessage error={error} />
          <form onSubmit={save}>
            <label>
              Legal name
              <input value={legalName} required maxLength={200} onChange={(e) => setLegalName(e.target.value)} />
            </label>
            <label>
              Home state
              <select value={homeState} onChange={(e) => setHomeState(e.target.value)}>
                <option value="">(not said yet)</option>
                {STATES.map((code) => (
                  <option key={code} value={code}>
                    {code}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Accounting method
              <select value={accountingMethod} onChange={(e) => setAccountingMethod(e.target.value)}>
                <option value="cash">Cash — counted when the money moves</option>
                <option value="accrual">Accrual — counted when it is earned or owed</option>
              </select>
            </label>
            <button type="submit" disabled={busy}>
              Save
            </button>
            {saved && <span role="status"> Saved.</span>}
          </form>
        </Card>
      )}
      <Card title="What goes on the documents you send">
        <p className="muted">
          Your address, how to reach you and how to be paid. All of it is optional. How to pay appears on
          invoices and statements only — a quote is not a bill, so it never carries payment instructions.
        </p>
        <ErrorMessage error={letterheadError} />
        <form onSubmit={saveLetterhead}>
          <label>
            Your address
            <textarea rows={4} maxLength={400} {...field('address')} />
          </label>
          <label>
            Phone
            <input maxLength={60} {...field('phone')} />
          </label>
          <label>
            Email on documents
            <input maxLength={200} {...field('email')} />
          </label>
          <label>
            Website
            <input maxLength={200} {...field('website')} />
          </label>
          <label>
            Tax id as you want it printed
            <input maxLength={60} {...field('taxId')} />
          </label>
          <label>
            How to pay you
            <textarea rows={3} maxLength={500} {...field('paymentInstructions')} />
          </label>
          <button type="submit">Save the letterhead</button>
          {letterheadSaved && <span role="status"> Saved.</span>}
        </form>
        <p>
          <label>
            Logo (PNG or JPEG, up to 1 MB)
            <input type="file" accept="image/png,image/jpeg" onChange={(e) => chooseLogo(e.target.files?.[0])} />
          </label>
        </p>
        {branding.value?.hasLogo && (
          <p>
            <img src={api.logoUrl(orgId, entityId)} alt="Your logo" style={{ maxHeight: 60 }} />{' '}
            <button
              type="button"
              onClick={() =>
                api
                  .deleteLogo(orgId, entityId)
                  .then(() => branding.reload())
                  .catch(setLetterheadError)
              }
            >
              Remove the logo
            </button>
          </p>
        )}
      </Card>
    </main>
  );
}
