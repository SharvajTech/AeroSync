import type { ReactNode } from 'react';
import { formatNumber } from '../../utils/format';

export interface Metric {
  id: string;
  label: string;
  value: number | null | undefined;
  icon: ReactNode;
  hint?: string;
}

/** Row of compact KPI cards; values come from the backend summary, null shows a placeholder. */
export function MetricCards({ metrics, loading = false }: { metrics: readonly Metric[]; loading?: boolean }) {
  return (
    <div className="metrics" aria-busy={loading}>
      {metrics.map((metric) => (
        <div className="metric" key={metric.id} data-testid={`metric-${metric.id}`} title={metric.hint}>
          <div className="metric__icon" aria-hidden>{metric.icon}</div>
          <div>
            <div className="metric__value">{loading ? <span className="skeleton skeleton--value" /> : formatNumber(metric.value)}</div>
            <div className="metric__label">{metric.label}</div>
          </div>
        </div>
      ))}
    </div>
  );
}
