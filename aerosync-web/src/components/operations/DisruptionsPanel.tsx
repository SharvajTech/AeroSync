import { useMemo, useState, type FormEvent } from 'react';
import { LuCircleCheck, LuCircleX, LuPlus, LuSparkles } from 'react-icons/lu';
import { api, keys } from '../../api/endpoints';
import { DISRUPTION_TYPES, type Disruption, type DisruptionType, type ResolutionProposal, type ResolutionSection } from '../../api/types';
import { useResource } from '../../hooks/useResource';
import { titleCase } from '../../utils/format';
import { Card } from '../common/Card';
import { StatusBadge } from '../common/StatusBadge';
import { EmptyState, ErrorState, LoadingState } from '../common/States';

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

export function disruptionTone(status: Disruption['status']): Tone {
  if (status === 'RESOLVED') return 'success';
  if (status === 'PROPOSED' || status === 'RESOLVING') return 'info';
  if (status === 'FAILED') return 'danger';
  return 'warning';
}

/** Open disruptions first, newest first. */
export function sortDisruptions(rows: Disruption[]): Disruption[] {
  const open = (d: Disruption) => (d.status === 'RESOLVED' ? 1 : 0);
  return [...rows].sort((a, b) => open(a) - open(b) || (b.detected_at ?? '').localeCompare(a.detected_at ?? '') || b.id - a.id);
}

const SECTIONS: { key: keyof ResolutionProposal; label: string }[] = [
  { key: 'gate_resolution', label: 'Gate' },
  { key: 'crew_resolution', label: 'Crew' },
  { key: 'passenger_resolution', label: 'Passengers' },
  { key: 'aircraft_resolution', label: 'Aircraft' },
];

interface Feedback {
  tone: 'success' | 'danger' | 'info';
  text: string;
}

/**
 * Disruptions of the selected airport with the resolution workflow:
 * propose (recommendation only) → approve (applied) or reject (re-proposed).
 * Every action is authorized by the backend again; buttons are only hidden
 * for read-only roles and for flights operated by another airport.
 */
export function DisruptionsPanel({ airport, canOperate }: { airport: string; canOperate: boolean }) {
  const disruptions = useResource(keys.disruptions(airport), () => api.disruptions(airport));
  const flights = useResource(keys.operationsFlights(airport), () => api.operationsFlights(airport));
  const [openId, setOpenId] = useState<number | null>(null);
  const [proposal, setProposal] = useState<ResolutionProposal | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [reporting, setReporting] = useState(false);

  const routes = useMemo(() => {
    const map = new Map<string, { origin: string; destination: string }>();
    (flights.data ?? []).forEach((f) => map.set(f.flight_id, { origin: f.origin, destination: f.destination }));
    return map;
  }, [flights.data]);
  const departing = useMemo(() => (flights.data ?? []).filter((f) => f.origin === airport), [flights.data, airport]);
  const rows = useMemo(() => sortDisruptions(disruptions.data ?? []), [disruptions.data]);

  const refresh = () => {
    disruptions.reload();
    flights.reload();
  };

  const run = async <T,>(label: string, action: () => Promise<T>): Promise<T | undefined> => {
    setBusy(label);
    setActionError(null);
    setFeedback(null);
    try {
      return await action();
    } catch (error) {
      setActionError(error);
      return undefined;
    } finally {
      setBusy(null);
    }
  };

  const propose = async (row: Disruption) => {
    setOpenId(row.id);
    setProposal(null);
    setRejectReason('');
    const result = await run(`propose:${row.id}`, () => api.proposeResolution(row.id));
    if (result) {
      setProposal(result);
      refresh();
    }
  };

  const approve = async (row: Disruption) => {
    if (!proposal) return;
    const result = await run(`approve:${row.id}`, () => api.approveResolution(row.id, proposal));
    if (result) {
      const note = result.cascade_result?.cascade_scope_note;
      setFeedback({ tone: 'success', text: `Resolution for ${row.flight_id} applied.${note ? ` ${note}` : ''}` });
      setOpenId(null);
      setProposal(null);
      refresh();
    }
  };

  const reject = async (row: Disruption) => {
    const reason = rejectReason.trim();
    if (!reason) {
      setFeedback({ tone: 'danger', text: 'Enter a reason so the agent can propose an alternative.' });
      return;
    }
    const result = await run(`reject:${row.id}`, () => api.rejectResolution(row.id, reason));
    if (result) {
      setProposal(result);
      setRejectReason('');
      setFeedback({ tone: 'info', text: 'Revised proposal ready for review.' });
    }
  };

  let body;
  if (disruptions.loading && !disruptions.data) body = <LoadingState label="Loading disruptions…" />;
  else if (disruptions.error) body = <ErrorState error={disruptions.error} onRetry={disruptions.reload} />;
  else if (rows.length === 0) body = <EmptyState title={`No disruptions at ${airport}`} detail="Disruptions of flights departing from or arriving at this airport appear here." />;
  else {
    body = (
      <div className="table-wrap">
        <table className="table" data-testid="disruptions-table">
          <thead>
            <tr>
              <th>Flight</th>
              <th>Route</th>
              <th>Type</th>
              <th>Delay</th>
              <th>Status</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const route = routes.get(row.flight_id);
              const ownDeparture = route?.origin === airport;
              const actionable = canOperate && ownDeparture && row.status !== 'RESOLVED';
              return (
                <DisruptionRow
                  key={row.id}
                  row={row}
                  route={route}
                  actionable={actionable}
                  hint={!ownDeparture && route ? `Handled by ${route.origin}` : null}
                  open={openId === row.id}
                  busy={busy}
                  proposal={openId === row.id ? proposal : null}
                  rejectReason={rejectReason}
                  onRejectReason={setRejectReason}
                  onPropose={() => propose(row)}
                  onApprove={() => approve(row)}
                  onReject={() => reject(row)}
                  onClose={() => {
                    setOpenId(null);
                    setProposal(null);
                  }}
                />
              );
            })}
          </tbody>
        </table>
      </div>
    );
  }

  return (
    <Card
      title="Disruptions"
      className="disruptions"
      actions={
        canOperate ? (
          <button type="button" className="button button--outline button--small" onClick={() => setReporting((v) => !v)} aria-expanded={reporting}>
            <LuPlus aria-hidden /> Report disruption
          </button>
        ) : undefined
      }
    >
      {!canOperate && (
        <p className="notice notice--info" data-testid="read-only-notice">
          Your role is read-only: you can review disruptions and resolutions but not act on them.
        </p>
      )}
      {reporting && canOperate && (
        <ReportForm
          airport={airport}
          flights={departing.map((f) => f.flight_id)}
          busy={busy === 'report'}
          onCancel={() => setReporting(false)}
          onSubmit={async (flightId, type, delay) => {
            const result = await run('report', () => api.reportDisruption(flightId, type, delay));
            if (result) {
              setReporting(false);
              setFeedback({ tone: 'success', text: `Disruption reported for ${result.flight_id}.` });
              refresh();
            }
          }}
        />
      )}
      {feedback && <p className={`notice notice--${feedback.tone}`} role="status">{feedback.text}</p>}
      {actionError ? <ErrorState error={actionError} /> : null}
      {body}
    </Card>
  );
}

function DisruptionRow({ row, route, actionable, hint, open, busy, proposal, rejectReason, onRejectReason, onPropose, onApprove, onReject, onClose }: {
  row: Disruption;
  route: { origin: string; destination: string } | undefined;
  actionable: boolean;
  hint: string | null;
  open: boolean;
  busy: string | null;
  proposal: ResolutionProposal | null;
  rejectReason: string;
  onRejectReason: (value: string) => void;
  onPropose: () => void;
  onApprove: () => void;
  onReject: () => void;
  onClose: () => void;
}) {
  return (
    <>
      <tr className={open ? 'is-selected' : undefined}>
        <td className="table__strong">{row.flight_id}</td>
        <td>{route ? `${route.origin} → ${route.destination}` : '—'}</td>
        <td>{titleCase(row.type)}</td>
        <td>{row.delay_minutes != null ? `${row.delay_minutes} min` : '—'}</td>
        <td><StatusBadge tone={disruptionTone(row.status)}>{titleCase(row.status)}</StatusBadge></td>
        <td className="table__actions">
          {actionable ? (
            <button type="button" className="button button--outline button--small" onClick={onPropose} disabled={busy !== null}>
              <LuSparkles aria-hidden /> {busy === `propose:${row.id}` ? 'Proposing…' : 'Propose resolution'}
            </button>
          ) : hint ? (
            <span className="details__muted">{hint}</span>
          ) : null}
        </td>
      </tr>
      {open && (
        <tr className="proposal-row">
          <td colSpan={6}>
            {busy === `propose:${row.id}` && !proposal ? (
              <LoadingState label="Asking the resolution agent…" />
            ) : proposal ? (
              <ProposalView
                proposal={proposal}
                busy={busy}
                id={row.id}
                rejectReason={rejectReason}
                onRejectReason={onRejectReason}
                onApprove={onApprove}
                onReject={onReject}
                onClose={onClose}
              />
            ) : null}
          </td>
        </tr>
      )}
    </>
  );
}

function sectionText(section: ResolutionSection | undefined): string {
  if (!section) return '—';
  const action = section.action ? titleCase(String(section.action)) : '';
  const target = section.new_gate ?? section.new_crew_id ?? '';
  const reason = section.reason ? String(section.reason) : '';
  return [action, target ? `(${String(target)})` : '', reason ? `— ${reason}` : ''].filter(Boolean).join(' ') || '—';
}

function ProposalView({ proposal, busy, id, rejectReason, onRejectReason, onApprove, onReject, onClose }: {
  proposal: ResolutionProposal;
  busy: string | null;
  id: number;
  rejectReason: string;
  onRejectReason: (value: string) => void;
  onApprove: () => void;
  onReject: () => void;
  onClose: () => void;
}) {
  return (
    <div className="proposal" data-testid="proposal">
      <div className="proposal__head">
        <span className="details__label">Recommendation (not applied yet)</span>
        {proposal.severity && <StatusBadge tone={proposal.severity === 'LOW' ? 'neutral' : 'warning'}>{titleCase(proposal.severity)}</StatusBadge>}
        {typeof proposal.estimated_new_delay_mins === 'number' && (
          <span className="details__muted">Estimated delay {proposal.estimated_new_delay_mins} min</span>
        )}
      </div>
      <dl className="kv proposal__sections">
        {SECTIONS.map(({ key, label }) => (
          <div key={key} className="kv__row">
            <dt>{label}</dt>
            <dd>{sectionText(proposal[key] as ResolutionSection | undefined)}</dd>
          </div>
        ))}
      </dl>
      {proposal.ai_explanation && <p className="proposal__explanation">{proposal.ai_explanation}</p>}
      <div className="proposal__actions">
        <button type="button" className="button button--primary button--small" onClick={onApprove} disabled={busy !== null} data-testid="approve">
          <LuCircleCheck aria-hidden /> {busy === `approve:${id}` ? 'Applying…' : 'Approve and apply'}
        </button>
        <input
          className="input input--small"
          placeholder="Reason for rejecting (e.g. CR-002 on leave)"
          aria-label="Rejection reason"
          value={rejectReason}
          onChange={(event) => onRejectReason(event.target.value)}
          disabled={busy !== null}
        />
        <button type="button" className="button button--outline button--small" onClick={onReject} disabled={busy !== null}>
          <LuCircleX aria-hidden /> {busy === `reject:${id}` ? 'Re-proposing…' : 'Reject'}
        </button>
        <button type="button" className="button button--ghost button--small" onClick={onClose} disabled={busy !== null}>
          Close
        </button>
      </div>
    </div>
  );
}

function ReportForm({ airport, flights, busy, onSubmit, onCancel }: {
  airport: string;
  flights: string[];
  busy: boolean;
  onSubmit: (flightId: string, type: DisruptionType, delayMinutes: number) => void;
  onCancel: () => void;
}) {
  const [flightId, setFlightId] = useState(flights[0] ?? '');
  const [type, setType] = useState<DisruptionType>('WEATHER_DELAY');
  const [delay, setDelay] = useState('60');
  const [error, setError] = useState<string | null>(null);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const minutes = Number(delay);
    if (!flightId) return setError(`No flights departing from ${airport} to report on.`);
    if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) return setError('Delay must be 1 to 1440 minutes.');
    setError(null);
    onSubmit(flightId, type, minutes);
  };

  return (
    <form className="report-form" onSubmit={submit} noValidate aria-label="Report disruption">
      <label className="field">
        <span className="field__label">Flight (departing {airport})</span>
        <select className="select" value={flightId} onChange={(e) => setFlightId(e.target.value)} disabled={busy || flights.length === 0}>
          {flights.length === 0 && <option value="">No departing flights</option>}
          {flights.map((f) => <option key={f} value={f}>{f}</option>)}
        </select>
      </label>
      <label className="field">
        <span className="field__label">Type</span>
        <select className="select" value={type} onChange={(e) => setType(e.target.value as DisruptionType)} disabled={busy}>
          {DISRUPTION_TYPES.map((t) => <option key={t} value={t}>{titleCase(t)}</option>)}
        </select>
      </label>
      <label className="field">
        <span className="field__label">Delay (min)</span>
        <input className="input" type="number" min={1} max={1440} value={delay} onChange={(e) => setDelay(e.target.value)} disabled={busy} />
      </label>
      <div className="report-form__actions">
        <button type="submit" className="button button--primary button--small" disabled={busy}>{busy ? 'Reporting…' : 'Report'}</button>
        <button type="button" className="button button--ghost button--small" onClick={onCancel} disabled={busy}>Cancel</button>
      </div>
      {error && <span className="field__error" role="alert">{error}</span>}
    </form>
  );
}
