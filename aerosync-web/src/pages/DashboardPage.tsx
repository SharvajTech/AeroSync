import { useState } from 'react';
import { LuLink, LuPlaneTakeoff, LuTowerControl, LuWaypoints } from 'react-icons/lu';
import { GraphExplorer } from '../components/GraphExplorer';
import { MetricCards, type Metric } from '../components/common/MetricCards';
import { ErrorState } from '../components/common/States';
import { PageHeader } from '../components/layout/PageHeader';
import type { RouteName } from '../hooks/useHashRoute';
import { useOperatingDate } from '../state/OperatingDate';
import { NETWORK_SCOPE, type GraphScope, type Selection } from '../state/selection';
import { formatDateShort } from '../utils/format';

export function DashboardPage({ navigate }: { navigate: (route: RouteName, params?: Record<string, string | null | undefined>) => void }) {
  const { date, network } = useOperatingDate();
  const [scope, setScope] = useState<GraphScope>(NETWORK_SCOPE);
  const [selection, setSelection] = useState<Selection>(null);
  const summary = network.data?.summary;

  const metrics: Metric[] = [
    { id: 'airports', label: 'Airports', value: summary?.airport_count, icon: <LuTowerControl /> },
    {
      id: 'flights',
      label: date ? `Flights (${formatDateShort(date)})` : 'Flights',
      value: summary?.flight_count,
      icon: <LuPlaneTakeoff />,
    },
    {
      id: 'routes',
      label: 'Route edges',
      value: summary?.route_edge_count,
      icon: <LuWaypoints />,
      hint: 'DEPARTS_FROM and ARRIVES_AT edges (two per flight)',
    },
    {
      id: 'dependencies',
      label: 'Dependency edges',
      value: summary?.dependency_edge_count,
      icon: <LuLink />,
      hint: 'Aircraft rotations, passenger connections, crew rotations and operational constraints between flights of this date',
    },
  ];

  return (
    <>
      <PageHeader
        title="Flight Network Dashboard"
        subtitle="Airport and flight connections across the operational network"
      />
      {network.error && !network.data ? (
        <ErrorState error={network.error} onRetry={network.reload} />
      ) : (
        <MetricCards metrics={metrics} loading={network.loading && !network.data} />
      )}
      <GraphExplorer
        scope={scope}
        onScopeChange={setScope}
        selection={selection}
        onSelect={setSelection}
        navigate={navigate}
      />
    </>
  );
}
