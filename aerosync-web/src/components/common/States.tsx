import type { ReactNode } from 'react';
import { LuCircleAlert, LuInbox, LuRefreshCw } from 'react-icons/lu';
import { describeError } from '../../api/client';

export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="state state--loading" role="status" aria-live="polite">
      <span className="spinner" aria-hidden />
      <span>{label}</span>
    </div>
  );
}

export function EmptyState({ title, detail, action }: { title: string; detail?: ReactNode; action?: ReactNode }) {
  return (
    <div className="state state--empty">
      <LuInbox className="state__icon" aria-hidden />
      <div className="state__title">{title}</div>
      {detail && <div className="state__detail">{detail}</div>}
      {action && <div className="state__action">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { title, detail } = describeError(error);
  return (
    <div className="state state--error" role="alert">
      <LuCircleAlert className="state__icon" aria-hidden />
      <div className="state__title">{title}</div>
      <div className="state__detail">{detail}</div>
      {onRetry && (
        <div className="state__action">
          <button type="button" className="button button--secondary" onClick={onRetry}>
            <LuRefreshCw aria-hidden /> Retry
          </button>
        </div>
      )}
    </div>
  );
}
