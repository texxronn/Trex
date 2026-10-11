// The Blotter's right-click menu: a fixed-position column of deliberate actions. It closes on a
// pick, an outside pointerdown, Escape, and any scroll or resize, so it never outlives its row.

import { el } from './dom.js';

// The one open menu's closer, so a keyboard-triggered contextmenu (which fires no outside
// pointerdown) replaces the old menu through the same path — listeners included.
let openMenu = null;

export function openContextMenu(x, y, items) {
  if (openMenu) openMenu();
  // Remember who opened this menu: on close, focus returns there so keyboard use (Escape after
  // Tab-ing through the items) does not drop the caret to the body.
  const opener = document.activeElement;
  const menu = el('div', { class: 'context-menu', role: 'menu' },
    ...items.map((item) => el('button', {
      type: 'button',
      role: 'menuitem',
      disabled: item.enabled === false,
      title: item.enabled === false ? item.hint : undefined,
      onclick: () => {
        if (item.enabled === false) return; // disabled items must never fire
        close();
        item.onPick();
      },
    }, item.label)));
  document.body.append(menu);
  // Measure after append, then clamp: a menu near the right or bottom edge flips inward instead of
  // being clipped. Positioning is synchronous, so the menu never paints at its static spot.
  const rect = menu.getBoundingClientRect();
  menu.style.left = Math.max(0, Math.min(x, window.innerWidth - rect.width)) + 'px';
  menu.style.top = Math.max(0, Math.min(y, window.innerHeight - rect.height)) + 'px';
  const first = menu.querySelector('button:not(:disabled)');
  if (first) first.focus(); // keyboard use: Tab/Enter reach the items without a pointer
  document.addEventListener('keydown', onKey);
  document.addEventListener('pointerdown', onOutside, true);
  window.addEventListener('scroll', close, true);
  window.addEventListener('resize', close);
  openMenu = close;

  function close() {
    const hadFocus = menu.contains(document.activeElement);
    menu.remove();
    document.removeEventListener('keydown', onKey);
    document.removeEventListener('pointerdown', onOutside, true);
    window.removeEventListener('scroll', close, true);
    window.removeEventListener('resize', close);
    if (openMenu === close) openMenu = null; // never clear a menu opened after this one
    if (hadFocus && opener && opener.isConnected) opener.focus();
  }

  function onKey(e) {
    if (e.key === 'Escape') close();
  }

  function onOutside(e) {
    if (!menu.contains(e.target)) close();
  }
}

/**
 * The visible affordance for the same menu: a compact ⋮ button on the row. It takes a factory so
 * the items are built when opened (the Blotter's depend on the current selection), and anchors the
 * menu under itself rather than at a cursor.
 */
export function kebabButton(items) {
  return el('button', {
    type: 'button',
    class: 'kebab',
    'aria-haspopup': 'menu',
    'aria-label': 'Row actions',
    title: 'Row actions',
    onclick: (e) => {
      const rect = e.currentTarget.getBoundingClientRect();
      openContextMenu(rect.left, rect.bottom, items());
    },
  }, '\u22ee');
}
