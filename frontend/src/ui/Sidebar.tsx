/*
  The left-hand menu shown inside an entity. What it lists comes from src/ui/navigation.ts — edit that file
  to add, remove or reorder pages; this one only draws them.

  On small screens the same menu slides in as a drawer: `open` says whether it is showing, and it is drawn off
  screen (layout.css, .sidebar) when not. On wide screens it is always visible and `open` changes nothing.
*/
import { Link, NavLink } from 'react-router-dom';
import { Icon } from './icons';
import { ENTITY_NAV } from './navigation';

export function Sidebar({ orgId, entityId, open }: { orgId: string; entityId: string; open: boolean }) {
  const base = `/orgs/${orgId}/entities/${entityId}`;
  return (
    <aside id="sidebar" className={open ? 'sidebar open' : 'sidebar'}>
      <Link to={`/orgs/${orgId}`} className="sidebar-back">
        <Icon name="back" size={16} />
        All entities
      </Link>
      <nav aria-label="Entity">
        {ENTITY_NAV.map((group) => (
          <div key={group.heading} className="sidebar-group">
            <p className="sidebar-heading">{group.heading}</p>
            {group.items.map((item) => (
              <NavLink
                key={item.label}
                to={item.path ? `${base}/${item.path}` : base}
                // The dashboard is the entity's own address, so it must match exactly or it would light up on
                // every page underneath it.
                end={item.path === ''}
                className={({ isActive }) => (isActive ? 'sidebar-link active' : 'sidebar-link')}
              >
                <Icon name={item.icon} />
                {item.label}
              </NavLink>
            ))}
          </div>
        ))}
      </nav>
    </aside>
  );
}
