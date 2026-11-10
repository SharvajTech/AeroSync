import type { ReactNode } from 'react';

export function Card({ title, actions, children, className = '', bodyClassName = '' }: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  bodyClassName?: string;
}) {
  return (
    <section className={`card ${className}`.trim()}>
      {(title || actions) && (
        <header className="card__header">
          {title && <h2 className="card__title">{title}</h2>}
          {actions && <div className="card__actions">{actions}</div>}
        </header>
      )}
      <div className={`card__body ${bodyClassName}`.trim()}>{children}</div>
    </section>
  );
}
