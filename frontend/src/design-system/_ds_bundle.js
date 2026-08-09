/* @ds-bundle: {"format":4,"namespace":"BeanFlowDesignSystem_c0ae52","components":[{"name":"BalanceCard","sourcePath":"components/commerce/BalanceCard.jsx"},{"name":"CouponCard","sourcePath":"components/commerce/CouponCard.jsx"},{"name":"DataTable","sourcePath":"components/commerce/DataTable.jsx"},{"name":"MenuItem","sourcePath":"components/commerce/MenuItem.jsx"},{"name":"OrderStatus","sourcePath":"components/commerce/OrderStatus.jsx"},{"name":"OrderTicket","sourcePath":"components/commerce/OrderTicket.jsx"},{"name":"PickupSlots","sourcePath":"components/commerce/PickupSlots.jsx"},{"name":"StatTile","sourcePath":"components/commerce/StatTile.jsx"},{"name":"StoreCard","sourcePath":"components/commerce/StoreCard.jsx"},{"name":"Badge","sourcePath":"components/core/Badge.jsx"},{"name":"Button","sourcePath":"components/core/Button.jsx"},{"name":"Card","sourcePath":"components/core/Card.jsx"},{"name":"Icon","sourcePath":"components/core/Icon.jsx"},{"name":"IconButton","sourcePath":"components/core/IconButton.jsx"},{"name":"SectionHeader","sourcePath":"components/core/SectionHeader.jsx"},{"name":"Alert","sourcePath":"components/feedback/Alert.jsx"},{"name":"Dialog","sourcePath":"components/feedback/Dialog.jsx"},{"name":"EmptyState","sourcePath":"components/feedback/EmptyState.jsx"},{"name":"ProgressBar","sourcePath":"components/feedback/ProgressBar.jsx"},{"name":"Toast","sourcePath":"components/feedback/Toast.jsx"},{"name":"Checkbox","sourcePath":"components/forms/Checkbox.jsx"},{"name":"Input","sourcePath":"components/forms/Input.jsx"},{"name":"QuantityStepper","sourcePath":"components/forms/QuantityStepper.jsx"},{"name":"Radio","sourcePath":"components/forms/Radio.jsx"},{"name":"SearchField","sourcePath":"components/forms/SearchField.jsx"},{"name":"Select","sourcePath":"components/forms/Select.jsx"},{"name":"Switch","sourcePath":"components/forms/Switch.jsx"},{"name":"ListRow","sourcePath":"components/navigation/ListRow.jsx"},{"name":"SideNav","sourcePath":"components/navigation/SideNav.jsx"},{"name":"TabBar","sourcePath":"components/navigation/TabBar.jsx"},{"name":"Tabs","sourcePath":"components/navigation/Tabs.jsx"},{"name":"TopBar","sourcePath":"components/navigation/TopBar.jsx"}],"sourceHashes":{"components/commerce/BalanceCard.jsx":"5e6fb5e48efa","components/commerce/CouponCard.jsx":"9054b9d8042b","components/commerce/DataTable.jsx":"f5e77111ac69","components/commerce/MenuItem.jsx":"812336ffdac5","components/commerce/OrderStatus.jsx":"b4e5edcb8f50","components/commerce/OrderTicket.jsx":"73e2f3ba0fb0","components/commerce/PickupSlots.jsx":"7c6b2b88716d","components/commerce/StatTile.jsx":"f54d4fa2a6fa","components/commerce/StoreCard.jsx":"f87a6b0659f5","components/core/Badge.jsx":"6e9810f1b811","components/core/Button.jsx":"56fc733cbcb1","components/core/Card.jsx":"a5463b6fea63","components/core/Icon.jsx":"7c3a0c4ac6ab","components/core/IconButton.jsx":"64241e7057a9","components/core/SectionHeader.jsx":"ae8c5e1adaa9","components/feedback/Alert.jsx":"5ac6c3fc49f1","components/feedback/Dialog.jsx":"dbd6ff231b86","components/feedback/EmptyState.jsx":"8a30653dddc1","components/feedback/ProgressBar.jsx":"e8b36044536e","components/feedback/Toast.jsx":"0ee343d9c92d","components/forms/Checkbox.jsx":"0b6351e7892d","components/forms/Input.jsx":"cf1c334b55fe","components/forms/QuantityStepper.jsx":"509da958b71b","components/forms/Radio.jsx":"459c7c56241e","components/forms/SearchField.jsx":"8c7f01959eb1","components/forms/Select.jsx":"4ff0581a1df0","components/forms/Switch.jsx":"966ac00e156f","components/navigation/ListRow.jsx":"a4592ec1e95b","components/navigation/SideNav.jsx":"c0fd11bf7abf","components/navigation/TabBar.jsx":"22dce5c9bf07","components/navigation/Tabs.jsx":"e199c77d78c3","components/navigation/TopBar.jsx":"000e9b59247b","ui_kits/customer_app/App.jsx":"80db820acef9","ui_kits/customer_app/CheckoutScreen.jsx":"f3bbb3718ced","ui_kits/customer_app/HomeScreen.jsx":"a8fd3a28f311","ui_kits/customer_app/OrderScreen.jsx":"d92465d370cb","ui_kits/customer_app/StoreScreen.jsx":"54baefdbdb76","ui_kits/customer_app/WalletScreen.jsx":"936852d9ff70","ui_kits/merchant_console/ConsoleApp.jsx":"614e0755a4ea","ui_kits/merchant_console/DashboardScreen.jsx":"aa2a94afbbf1","ui_kits/merchant_console/PosScreen.jsx":"42500860dd02","ui_kits/merchant_console/SettlementScreen.jsx":"b2f66e1ee99f","ui_kits/merchant_console/StockScreen.jsx":"7ac1da1a4b63"},"inlinedExternals":[],"unexposedExports":[]} */

(() => {

const __ds_ns = (window.BeanFlowDesignSystem_c0ae52 = window.BeanFlowDesignSystem_c0ae52 || {});

const __ds_scope = {};

(__ds_ns.__errors = __ds_ns.__errors || []);

// components/commerce/CouponCard.jsx
try { (() => {
function CouponCard({
  amount,
  amountUnit = '원',
  name,
  condition,
  expiry,
  expiringSoon = false,
  used = false,
  action,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("div", {
    className: ['bf-coupon', used && 'bf-coupon--used', className].filter(Boolean).join(' ')
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-coupon__amt"
  }, /*#__PURE__*/React.createElement("b", {
    className: "bf-num"
  }, typeof amount === 'number' ? amount.toLocaleString('ko-KR') : amount), /*#__PURE__*/React.createElement("span", null, amountUnit, " \uD560\uC778")), /*#__PURE__*/React.createElement("div", {
    className: "bf-coupon__body"
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-coupon__name"
  }, name), condition && /*#__PURE__*/React.createElement("div", {
    className: "bf-coupon__cond"
  }, condition), expiry && /*#__PURE__*/React.createElement("div", {
    className: expiringSoon ? 'bf-coupon__exp' : 'bf-coupon__cond'
  }, expiry)), action && /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      padding: '0 var(--sp-5)'
    }
  }, action));
}
Object.assign(__ds_scope, { CouponCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/commerce/CouponCard.jsx", error: String((e && e.message) || e) }); }

// components/commerce/DataTable.jsx
try { (() => {
function DataTable({
  columns = [],
  rows = [],
  empty = '데이터가 없어요',
  className = ''
}) {
  return /*#__PURE__*/React.createElement("table", {
    className: `bf-table ${className}`
  }, /*#__PURE__*/React.createElement("thead", null, /*#__PURE__*/React.createElement("tr", null, columns.map(c => /*#__PURE__*/React.createElement("th", {
    key: c.key,
    style: c.align === 'right' ? {
      textAlign: 'right'
    } : undefined
  }, c.header)))), /*#__PURE__*/React.createElement("tbody", null, rows.length === 0 ? /*#__PURE__*/React.createElement("tr", null, /*#__PURE__*/React.createElement("td", {
    colSpan: columns.length,
    style: {
      textAlign: 'center',
      color: 'var(--text-faint)',
      padding: 'var(--sp-10)'
    }
  }, empty)) : rows.map((r, i) => /*#__PURE__*/React.createElement("tr", {
    key: r.id || i
  }, columns.map(c => {
    const v = c.render ? c.render(r) : r[c.key];
    const neg = c.align === 'right' && typeof r[c.key] === 'number' && r[c.key] < 0;
    return /*#__PURE__*/React.createElement("td", {
      key: c.key,
      className: [c.align === 'right' && 'is-num', neg && 'is-neg'].filter(Boolean).join(' ')
    }, typeof v === 'number' ? v.toLocaleString('ko-KR') : v);
  })))));
}
Object.assign(__ds_scope, { DataTable });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/commerce/DataTable.jsx", error: String((e && e.message) || e) }); }

// components/commerce/OrderStatus.jsx
try { (() => {
const LABEL = {
  placed: '주문 접수',
  making: '제조 중',
  ready: '준비 완료',
  picked: '픽업 완료',
  canceled: '주문 취소',
  refund: '환불 처리중'
};
function OrderStatus({
  status,
  label,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("span", {
    className: `bf-status bf-status--${status} ${className}`
  }, /*#__PURE__*/React.createElement("i", {
    className: "bf-status__dot"
  }), label || LABEL[status]);
}
Object.assign(__ds_scope, { OrderStatus });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/commerce/OrderStatus.jsx", error: String((e && e.message) || e) }); }

// components/commerce/OrderTicket.jsx
try { (() => {
function OrderTicket({
  number,
  status = 'placed',
  dueAt,
  customer,
  lines = [],
  actions,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("div", {
    className: `bf-ticket bf-ticket--${status} ${className}`
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-ticket__head"
  }, /*#__PURE__*/React.createElement("span", {
    className: "bf-ticket__no"
  }, number), /*#__PURE__*/React.createElement(__ds_scope.OrderStatus, {
    status: status
  })), /*#__PURE__*/React.createElement("div", {
    className: "bf-ticket__due"
  }, "\uD53D\uC5C5 ", dueAt, customer ? ` · ${customer}` : ''), /*#__PURE__*/React.createElement("div", {
    className: "bf-ticket__lines"
  }, lines.map((l, i) => /*#__PURE__*/React.createElement("div", {
    className: "bf-ticket__line",
    key: i
  }, /*#__PURE__*/React.createElement("span", {
    className: "bf-ticket__qty"
  }, l.qty), /*#__PURE__*/React.createElement("span", null, /*#__PURE__*/React.createElement("div", {
    style: {
      color: 'var(--text-strong)',
      fontWeight: 'var(--fw-medium)'
    }
  }, l.name), l.options && /*#__PURE__*/React.createElement("div", {
    className: "bf-ticket__opt"
  }, l.options))))), actions && /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 'var(--sp-4)'
    }
  }, actions));
}
Object.assign(__ds_scope, { OrderTicket });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/commerce/OrderTicket.jsx", error: String((e && e.message) || e) }); }

// components/commerce/PickupSlots.jsx
try { (() => {
function PickupSlots({
  slots = [],
  value,
  onChange,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("div", {
    className: `bf-slots ${className}`
  }, slots.map(s => {
    const full = s.remaining <= 0;
    return /*#__PURE__*/React.createElement("button", {
      key: s.time,
      className: ['bf-slot', !full && s.remaining <= 2 && 'bf-slot--tight'].filter(Boolean).join(' '),
      "aria-pressed": value === s.time,
      disabled: full,
      onClick: () => onChange && onChange(s.time)
    }, /*#__PURE__*/React.createElement("span", {
      className: "bf-slot__time"
    }, s.time), /*#__PURE__*/React.createElement("span", {
      className: "bf-slot__cap"
    }, full ? '마감' : `${s.remaining}잔 가능`));
  }));
}
Object.assign(__ds_scope, { PickupSlots });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/commerce/PickupSlots.jsx", error: String((e && e.message) || e) }); }

// components/core/Badge.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Badge({
  children,
  tone = 'neutral',
  size = 'sm',
  dot = false,
  square = false,
  className = '',
  ...rest
}) {
  return /*#__PURE__*/React.createElement("span", _extends({
    className: ['bf-badge', `bf-badge--${tone}`, size === 'lg' && 'bf-badge--lg', square && 'bf-badge--square', className].filter(Boolean).join(' ')
  }, rest), dot && /*#__PURE__*/React.createElement("i", {
    className: "bf-badge__dot"
  }), children);
}
Object.assign(__ds_scope, { Badge });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Badge.jsx", error: String((e && e.message) || e) }); }

// components/commerce/MenuItem.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function MenuItem({
  name,
  description,
  price,
  originalPrice,
  image,
  soldOut = false,
  badge,
  trailing,
  onClick,
  className = '',
  ...rest
}) {
  const won = n => n.toLocaleString('ko-KR') + '원';
  return /*#__PURE__*/React.createElement("button", _extends({
    className: ['bf-menuitem', soldOut && 'bf-menuitem--sold', className].filter(Boolean).join(' '),
    disabled: soldOut,
    onClick: onClick
  }, rest), /*#__PURE__*/React.createElement("div", {
    className: "bf-menuitem__thumb",
    style: image ? {
      backgroundImage: `url(${image})`
    } : undefined
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 'var(--sp-4)'
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "bf-menuitem__name"
  }, name), soldOut ? /*#__PURE__*/React.createElement(__ds_scope.Badge, {
    tone: "neutral"
  }, "\uD488\uC808") : badge ? /*#__PURE__*/React.createElement(__ds_scope.Badge, {
    tone: "accent"
  }, badge) : null), description && /*#__PURE__*/React.createElement("div", {
    className: "bf-menuitem__desc"
  }, description), /*#__PURE__*/React.createElement("div", {
    className: "bf-menuitem__price"
  }, originalPrice && /*#__PURE__*/React.createElement("span", {
    className: "bf-menuitem__strike"
  }, won(originalPrice)), won(price))), trailing);
}
Object.assign(__ds_scope, { MenuItem });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/commerce/MenuItem.jsx", error: String((e && e.message) || e) }); }

// components/core/Card.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Card({
  children,
  variant = 'default',
  padded = false,
  interactive = false,
  as: Tag = 'div',
  className = '',
  ...rest
}) {
  return /*#__PURE__*/React.createElement(Tag, _extends({
    className: ['bf-card', variant !== 'default' && `bf-card--${variant}`, padded && 'bf-card__pad', interactive && 'bf-card--interactive', className].filter(Boolean).join(' ')
  }, rest), children);
}
Object.assign(__ds_scope, { Card });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Card.jsx", error: String((e && e.message) || e) }); }

// components/core/Icon.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const CDN = 'https://cdn.jsdelivr.net/npm/lucide-static@0.544.0/icons/';
function Icon({
  name,
  size = 20,
  className = '',
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("span", _extends({
    className: `bf-icon ${className}`,
    style: {
      width: size,
      height: size,
      '--i': `url("${CDN}${name}.svg")`,
      ...style
    },
    "aria-hidden": "true"
  }, rest));
}
Object.assign(__ds_scope, { Icon });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Icon.jsx", error: String((e && e.message) || e) }); }

// components/commerce/BalanceCard.jsx
try { (() => {
function BalanceCard({
  kind = 'points',
  label,
  value,
  unit,
  caption,
  action,
  className = ''
}) {
  const isWallet = kind === 'wallet';
  return /*#__PURE__*/React.createElement("div", {
    className: ['bf-points', isWallet && 'bf-points--wallet', className].filter(Boolean).join(' ')
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-points__label"
  }, label || (isWallet ? 'BeanFlow 지갑 잔액' : '사용 가능 포인트')), /*#__PURE__*/React.createElement("div", {
    className: "bf-points__value bf-num"
  }, typeof value === 'number' ? value.toLocaleString('ko-KR') : value, /*#__PURE__*/React.createElement("small", null, unit || (isWallet ? '원' : 'P'))), /*#__PURE__*/React.createElement("div", {
    className: "bf-points__foot"
  }, /*#__PURE__*/React.createElement("span", null, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: isWallet ? 'wallet' : 'clock',
    size: 14,
    style: {
      verticalAlign: -2,
      marginRight: 5
    }
  }), caption), action));
}
Object.assign(__ds_scope, { BalanceCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/commerce/BalanceCard.jsx", error: String((e && e.message) || e) }); }

// components/commerce/StatTile.jsx
try { (() => {
function StatTile({
  label,
  value,
  unit,
  delta,
  trend = 'flat',
  caption,
  className = ''
}) {
  const glyph = trend === 'up' ? 'trending-up' : trend === 'down' ? 'trending-down' : 'minus';
  return /*#__PURE__*/React.createElement("div", {
    className: ['bf-card', 'bf-stat', className].filter(Boolean).join(' ')
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-stat__label"
  }, label), /*#__PURE__*/React.createElement("div", {
    className: "bf-stat__value"
  }, typeof value === 'number' ? value.toLocaleString('ko-KR') : value, unit && /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 'var(--fs-body)',
      fontWeight: 'var(--fw-semibold)',
      marginLeft: 3,
      color: 'var(--text-muted)'
    }
  }, unit)), (delta || caption) && /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 'var(--sp-4)'
    }
  }, delta && /*#__PURE__*/React.createElement("span", {
    className: `bf-stat__delta bf-stat__delta--${trend}`
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: glyph,
    size: 14
  }), delta), caption && /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 'var(--fs-caption)',
      color: 'var(--text-faint)'
    }
  }, caption)));
}
Object.assign(__ds_scope, { StatTile });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/commerce/StatTile.jsx", error: String((e && e.message) || e) }); }

// components/commerce/StoreCard.jsx
try { (() => {
function StoreCard({
  name,
  distance,
  walkMinutes,
  waitMinutes,
  tags = [],
  image,
  open = true,
  onClick,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("div", {
    className: ['bf-card', 'bf-card--interactive', 'bf-store', className].filter(Boolean).join(' '),
    onClick: onClick,
    role: onClick ? 'button' : undefined
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-store__thumb",
    style: image ? {
      backgroundImage: `url(${image})`
    } : undefined
  }, !image && /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'grid',
      placeItems: 'center',
      height: '100%',
      color: 'var(--crema-500)'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "store",
    size: 22
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      minWidth: 0,
      flex: 1
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-store__name"
  }, name), /*#__PURE__*/React.createElement("div", {
    className: "bf-store__meta"
  }, /*#__PURE__*/React.createElement("span", null, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "map-pin",
    size: 13,
    style: {
      verticalAlign: -2,
      marginRight: 3
    }
  }), distance), /*#__PURE__*/React.createElement("i", {
    className: "bf-store__dot"
  }), /*#__PURE__*/React.createElement("span", null, "\uB3C4\uBCF4 ", walkMinutes, "\uBD84")), tags.length > 0 && /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 'var(--sp-3)',
      marginTop: 'var(--sp-4)'
    }
  }, tags.map(t => /*#__PURE__*/React.createElement(__ds_scope.Badge, {
    key: t,
    tone: "accent"
  }, t)))), /*#__PURE__*/React.createElement("div", {
    className: "bf-store__wait"
  }, open ? /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("b", null, waitMinutes, "\uBD84"), /*#__PURE__*/React.createElement("span", null, "\uC608\uC0C1 \uB300\uAE30")) : /*#__PURE__*/React.createElement(__ds_scope.Badge, {
    tone: "neutral"
  }, "\uC601\uC5C5 \uC885\uB8CC")));
}
Object.assign(__ds_scope, { StoreCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/commerce/StoreCard.jsx", error: String((e && e.message) || e) }); }

// components/core/Button.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Button({
  children,
  variant = 'primary',
  size = 'md',
  block = false,
  pill = false,
  iconLeft,
  iconRight,
  loading = false,
  disabled = false,
  className = '',
  ...rest
}) {
  const cls = ['bf-btn', `bf-btn--${variant}`, `bf-btn--${size}`, block && 'bf-btn--block', pill && 'bf-btn--pill', className].filter(Boolean).join(' ');
  const gl = size === 'sm' ? 14 : size === 'lg' || size === 'xl' ? 18 : 16;
  return /*#__PURE__*/React.createElement("button", _extends({
    className: cls,
    disabled: disabled || loading
  }, rest), loading ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "loader-circle",
    size: gl,
    style: {
      animation: 'bf-spin 900ms linear infinite'
    }
  }) : iconLeft ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: iconLeft,
    size: gl
  }) : null, children, iconRight && !loading ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: iconRight,
    size: gl
  }) : null);
}
Object.assign(__ds_scope, { Button });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Button.jsx", error: String((e && e.message) || e) }); }

// components/core/IconButton.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function IconButton({
  icon,
  label,
  size = 'md',
  variant = 'ghost',
  className = '',
  ...rest
}) {
  const gl = size === 'sm' ? 16 : size === 'lg' ? 22 : 20;
  return /*#__PURE__*/React.createElement("button", _extends({
    className: ['bf-iconbtn', `bf-iconbtn--${size}`, `bf-iconbtn--${variant}`, className].filter(Boolean).join(' '),
    "aria-label": label,
    title: label
  }, rest), /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: gl
  }));
}
Object.assign(__ds_scope, { IconButton });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/IconButton.jsx", error: String((e && e.message) || e) }); }

// components/core/SectionHeader.jsx
try { (() => {
function SectionHeader({
  title,
  eyebrow,
  description,
  action,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("header", {
    className: `bf-section ${className}`
  }, /*#__PURE__*/React.createElement("div", null, eyebrow && /*#__PURE__*/React.createElement("div", {
    className: "bf-section__eyebrow"
  }, eyebrow), /*#__PURE__*/React.createElement("div", {
    className: "bf-section__title"
  }, title), description && /*#__PURE__*/React.createElement("div", {
    className: "bf-section__desc"
  }, description)), action);
}
Object.assign(__ds_scope, { SectionHeader });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/SectionHeader.jsx", error: String((e && e.message) || e) }); }

// components/feedback/Alert.jsx
try { (() => {
const GLYPH = {
  info: 'info',
  success: 'circle-check',
  warning: 'triangle-alert',
  danger: 'octagon-alert'
};
function Alert({
  tone = 'info',
  title,
  children,
  action,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("div", {
    className: `bf-alert bf-alert--${tone} ${className}`,
    role: tone === 'danger' ? 'alert' : 'status'
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: GLYPH[tone],
    size: 18,
    style: {
      marginTop: 1
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }, title && /*#__PURE__*/React.createElement("div", {
    className: "bf-alert__title"
  }, title), /*#__PURE__*/React.createElement("div", {
    className: "bf-alert__body"
  }, children)), action);
}
Object.assign(__ds_scope, { Alert });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/Alert.jsx", error: String((e && e.message) || e) }); }

// components/feedback/Dialog.jsx
try { (() => {
function Dialog({
  open = true,
  title,
  description,
  children,
  actions,
  sheet = false,
  onClose,
  className = ''
}) {
  if (!open) return null;
  return /*#__PURE__*/React.createElement("div", {
    className: "bf-dialog__scrim",
    style: sheet ? {
      alignItems: 'flex-end',
      padding: 0
    } : undefined,
    onClick: onClose
  }, /*#__PURE__*/React.createElement("div", {
    className: ['bf-dialog', sheet && 'bf-dialog--sheet', className].filter(Boolean).join(' '),
    role: "dialog",
    "aria-modal": "true",
    onClick: e => e.stopPropagation()
  }, sheet && /*#__PURE__*/React.createElement("div", {
    className: "bf-dialog__grip"
  }), title && /*#__PURE__*/React.createElement("div", {
    className: "bf-dialog__title"
  }, title), description && /*#__PURE__*/React.createElement("div", {
    className: "bf-dialog__desc"
  }, description), children, actions && /*#__PURE__*/React.createElement("div", {
    className: "bf-dialog__actions"
  }, actions)));
}
Object.assign(__ds_scope, { Dialog });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/Dialog.jsx", error: String((e && e.message) || e) }); }

// components/feedback/EmptyState.jsx
try { (() => {
function EmptyState({
  icon = 'coffee',
  title,
  description,
  action,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("div", {
    className: `bf-empty ${className}`
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-empty__glyph"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 26
  })), /*#__PURE__*/React.createElement("div", {
    className: "bf-empty__title"
  }, title), description && /*#__PURE__*/React.createElement("div", {
    className: "bf-empty__desc"
  }, description), action && /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 'var(--sp-4)'
    }
  }, action));
}
Object.assign(__ds_scope, { EmptyState });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/EmptyState.jsx", error: String((e && e.message) || e) }); }

// components/feedback/ProgressBar.jsx
try { (() => {
function ProgressBar({
  value = 0,
  max = 100,
  label,
  valueLabel,
  tone = 'accent',
  className = ''
}) {
  const pct = Math.max(0, Math.min(100, value / max * 100));
  return /*#__PURE__*/React.createElement("div", {
    className: className
  }, (label || valueLabel) && /*#__PURE__*/React.createElement("div", {
    className: "bf-progress__meta"
  }, /*#__PURE__*/React.createElement("span", null, label), /*#__PURE__*/React.createElement("span", {
    className: "bf-num"
  }, valueLabel)), /*#__PURE__*/React.createElement("div", {
    className: ['bf-progress', tone === 'brand' && 'bf-progress--brand'].filter(Boolean).join(' '),
    role: "progressbar",
    "aria-valuenow": value,
    "aria-valuemax": max
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-progress__fill",
    style: {
      width: `${pct}%`
    }
  })));
}
Object.assign(__ds_scope, { ProgressBar });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/ProgressBar.jsx", error: String((e && e.message) || e) }); }

// components/feedback/Toast.jsx
try { (() => {
function Toast({
  title,
  children,
  icon = 'bell-ring',
  tone = 'dark',
  action,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("div", {
    className: ['bf-toast', tone === 'accent' && 'bf-toast--accent', className].filter(Boolean).join(' '),
    role: "status"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 20
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }, title && /*#__PURE__*/React.createElement("div", {
    className: "bf-toast__title"
  }, title), children), action);
}
Object.assign(__ds_scope, { Toast });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/Toast.jsx", error: String((e && e.message) || e) }); }

// components/forms/Checkbox.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Checkbox({
  label,
  description,
  className = '',
  ...rest
}) {
  return /*#__PURE__*/React.createElement("label", {
    className: `bf-check ${className}`
  }, /*#__PURE__*/React.createElement("input", _extends({
    type: "checkbox"
  }, rest)), /*#__PURE__*/React.createElement("span", {
    className: "bf-check__box"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "check",
    size: 14
  })), /*#__PURE__*/React.createElement("span", null, label, description && /*#__PURE__*/React.createElement("span", {
    className: "bf-field__hint",
    style: {
      display: 'block',
      marginTop: 2
    }
  }, description)));
}
Object.assign(__ds_scope, { Checkbox });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Checkbox.jsx", error: String((e && e.message) || e) }); }

// components/forms/Input.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Input({
  label,
  hint,
  error,
  required = false,
  size = 'md',
  iconLeft,
  suffix,
  disabled = false,
  id,
  className = '',
  ...rest
}) {
  const inputId = id || `bf-${Math.random().toString(36).slice(2, 8)}`;
  return /*#__PURE__*/React.createElement("div", {
    className: `bf-field ${className}`
  }, label && /*#__PURE__*/React.createElement("label", {
    className: "bf-field__label",
    htmlFor: inputId
  }, label, required && /*#__PURE__*/React.createElement("span", {
    className: "bf-field__req"
  }, "*")), /*#__PURE__*/React.createElement("div", {
    className: ['bf-input', size !== 'md' && `bf-input--${size}`, error && 'bf-input--error', disabled && 'bf-input--disabled'].filter(Boolean).join(' ')
  }, iconLeft && /*#__PURE__*/React.createElement("span", {
    className: "bf-input__affix"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: iconLeft,
    size: 18
  })), /*#__PURE__*/React.createElement("input", _extends({
    id: inputId,
    className: "bf-input__el",
    disabled: disabled
  }, rest)), suffix && /*#__PURE__*/React.createElement("span", {
    className: "bf-input__affix"
  }, suffix)), error ? /*#__PURE__*/React.createElement("div", {
    className: "bf-field__error"
  }, error) : hint ? /*#__PURE__*/React.createElement("div", {
    className: "bf-field__hint"
  }, hint) : null);
}
Object.assign(__ds_scope, { Input });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Input.jsx", error: String((e && e.message) || e) }); }

// components/forms/QuantityStepper.jsx
try { (() => {
function QuantityStepper({
  value = 1,
  min = 1,
  max = 99,
  onChange,
  className = ''
}) {
  const set = n => onChange && onChange(Math.min(max, Math.max(min, n)));
  return /*#__PURE__*/React.createElement("div", {
    className: `bf-stepper ${className}`
  }, /*#__PURE__*/React.createElement("button", {
    className: "bf-stepper__btn",
    onClick: () => set(value - 1),
    disabled: value <= min,
    "aria-label": "\uC218\uB7C9 \uC904\uC774\uAE30"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "minus",
    size: 14
  })), /*#__PURE__*/React.createElement("span", {
    className: "bf-stepper__val"
  }, value), /*#__PURE__*/React.createElement("button", {
    className: "bf-stepper__btn",
    onClick: () => set(value + 1),
    disabled: value >= max,
    "aria-label": "\uC218\uB7C9 \uB298\uB9AC\uAE30"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "plus",
    size: 14
  })));
}
Object.assign(__ds_scope, { QuantityStepper });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/QuantityStepper.jsx", error: String((e && e.message) || e) }); }

// components/forms/Radio.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Radio({
  label,
  description,
  className = '',
  ...rest
}) {
  return /*#__PURE__*/React.createElement("label", {
    className: `bf-radio ${className}`
  }, /*#__PURE__*/React.createElement("input", _extends({
    type: "radio"
  }, rest)), /*#__PURE__*/React.createElement("span", {
    className: "bf-radio__dot"
  }), /*#__PURE__*/React.createElement("span", null, label, description && /*#__PURE__*/React.createElement("span", {
    className: "bf-field__hint",
    style: {
      display: 'block',
      marginTop: 2
    }
  }, description)));
}
Object.assign(__ds_scope, { Radio });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Radio.jsx", error: String((e && e.message) || e) }); }

// components/forms/SearchField.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function SearchField({
  value,
  onChange,
  placeholder = '매장·메뉴 검색',
  onClear,
  size = 'md',
  className = '',
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", {
    className: ['bf-input', 'bf-search', size !== 'md' && `bf-input--${size}`, className].filter(Boolean).join(' ')
  }, /*#__PURE__*/React.createElement("span", {
    className: "bf-input__affix"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "search",
    size: 18
  })), /*#__PURE__*/React.createElement("input", _extends({
    className: "bf-input__el",
    value: value,
    onChange: onChange,
    placeholder: placeholder
  }, rest)), value ? /*#__PURE__*/React.createElement("button", {
    className: "bf-iconbtn bf-iconbtn--sm",
    "aria-label": "\uC9C0\uC6B0\uAE30",
    onClick: onClear
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "x",
    size: 16
  })) : null);
}
Object.assign(__ds_scope, { SearchField });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/SearchField.jsx", error: String((e && e.message) || e) }); }

// components/forms/Select.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Select({
  label,
  hint,
  options = [],
  size = 'md',
  className = '',
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", {
    className: `bf-field ${className}`
  }, label && /*#__PURE__*/React.createElement("label", {
    className: "bf-field__label"
  }, label), /*#__PURE__*/React.createElement("div", {
    className: "bf-select"
  }, /*#__PURE__*/React.createElement("select", _extends({
    className: "bf-select__el",
    style: size === 'sm' ? {
      height: 'var(--control-h-sm)'
    } : undefined
  }, rest), options.map(o => /*#__PURE__*/React.createElement("option", {
    key: o.value,
    value: o.value
  }, o.label))), /*#__PURE__*/React.createElement("span", {
    className: "bf-select__caret"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "chevron-down",
    size: 16
  }))), hint && /*#__PURE__*/React.createElement("div", {
    className: "bf-field__hint"
  }, hint));
}
Object.assign(__ds_scope, { Select });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Select.jsx", error: String((e && e.message) || e) }); }

// components/forms/Switch.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Switch({
  label,
  className = '',
  ...rest
}) {
  return /*#__PURE__*/React.createElement("label", {
    className: `bf-switch ${className}`
  }, /*#__PURE__*/React.createElement("input", _extends({
    type: "checkbox",
    role: "switch"
  }, rest)), /*#__PURE__*/React.createElement("span", {
    className: "bf-switch__track"
  }, /*#__PURE__*/React.createElement("span", {
    className: "bf-switch__knob"
  })), label && /*#__PURE__*/React.createElement("span", null, label));
}
Object.assign(__ds_scope, { Switch });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Switch.jsx", error: String((e && e.message) || e) }); }

// components/navigation/ListRow.jsx
try { (() => {
function ListRow({
  leading,
  title,
  subtitle,
  trailing,
  chevron = false,
  onClick,
  className = ''
}) {
  const Tag = onClick ? 'button' : 'div';
  return /*#__PURE__*/React.createElement(Tag, {
    className: ['bf-row', !onClick && 'bf-row--static', className].filter(Boolean).join(' '),
    onClick: onClick
  }, leading, /*#__PURE__*/React.createElement("div", {
    style: {
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-row__title"
  }, title), subtitle && /*#__PURE__*/React.createElement("div", {
    className: "bf-row__sub"
  }, subtitle)), /*#__PURE__*/React.createElement("div", {
    className: "bf-row__trail"
  }, trailing, chevron && /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "chevron-right",
    size: 18
  })));
}
Object.assign(__ds_scope, { ListRow });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/ListRow.jsx", error: String((e && e.message) || e) }); }

// components/navigation/SideNav.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function SideNav({
  header,
  groups = [],
  value,
  onChange,
  footer,
  className = '',
  ...rest
}) {
  return /*#__PURE__*/React.createElement("nav", _extends({
    className: `bf-sidenav ${className}`
  }, rest), header, groups.map((g, i) => /*#__PURE__*/React.createElement(React.Fragment, {
    key: i
  }, g.label && /*#__PURE__*/React.createElement("div", {
    className: "bf-sidenav__group"
  }, g.label), g.items.map(it => /*#__PURE__*/React.createElement("button", {
    key: it.value,
    className: "bf-sidenav__item",
    "aria-current": it.value === value ? 'page' : undefined,
    onClick: () => onChange && onChange(it.value)
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: it.icon,
    size: 18
  }), it.label, it.badge != null && /*#__PURE__*/React.createElement("span", {
    className: "bf-sidenav__badge bf-num"
  }, it.badge))))), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }), footer);
}
Object.assign(__ds_scope, { SideNav });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/SideNav.jsx", error: String((e && e.message) || e) }); }

// components/navigation/TabBar.jsx
try { (() => {
function TabBar({
  items = [],
  value,
  onChange,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("nav", {
    className: `bf-tabbar ${className}`
  }, items.map(t => /*#__PURE__*/React.createElement("button", {
    key: t.value,
    className: "bf-tabbar__item",
    "aria-current": t.value === value ? 'page' : undefined,
    onClick: () => onChange && onChange(t.value)
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: t.icon,
    size: 22
  }), t.label)));
}
Object.assign(__ds_scope, { TabBar });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/TabBar.jsx", error: String((e && e.message) || e) }); }

// components/navigation/Tabs.jsx
try { (() => {
function Tabs({
  items = [],
  value,
  onChange,
  variant = 'underline',
  className = ''
}) {
  return /*#__PURE__*/React.createElement("div", {
    className: ['bf-tabs', variant === 'pill' && 'bf-tabs--pill', className].filter(Boolean).join(' '),
    role: "tablist"
  }, items.map(t => /*#__PURE__*/React.createElement("button", {
    key: t.value,
    role: "tab",
    "aria-selected": t.value === value,
    className: "bf-tabs__tab",
    onClick: () => onChange && onChange(t.value)
  }, t.label, t.count != null && /*#__PURE__*/React.createElement("span", {
    className: "bf-num",
    style: {
      marginLeft: 6,
      color: 'var(--text-faint)'
    }
  }, t.count))));
}
Object.assign(__ds_scope, { Tabs });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/Tabs.jsx", error: String((e && e.message) || e) }); }

// components/navigation/TopBar.jsx
try { (() => {
function TopBar({
  title,
  back = false,
  onBack,
  actions,
  transparent = false,
  leading,
  className = ''
}) {
  return /*#__PURE__*/React.createElement("header", {
    className: ['bf-topbar', transparent && 'bf-topbar--transparent', className].filter(Boolean).join(' ')
  }, back && /*#__PURE__*/React.createElement(__ds_scope.IconButton, {
    icon: "chevron-left",
    label: "\uB4A4\uB85C",
    onClick: onBack
  }), leading, title && /*#__PURE__*/React.createElement("div", {
    className: "bf-topbar__title"
  }, title), /*#__PURE__*/React.createElement("div", {
    className: "bf-topbar__spacer"
  }), actions);
}
Object.assign(__ds_scope, { TopBar });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/TopBar.jsx", error: String((e && e.message) || e) }); }

// ui_kits/customer_app/App.jsx
try { (() => {
const {
  TabBar,
  Toast
} = window.BeanFlowDesignSystem_c0ae52;
function CustomerApp() {
  const [tab, setTab] = React.useState('home');
  const [screen, setScreen] = React.useState('home');
  const [cart, setCart] = React.useState([]);
  const [slot, setSlot] = React.useState('12:20');
  const [toast, setToast] = React.useState(null);
  const go = s => {
    setScreen(s);
    if (['home', 'store2', 'order', 'wallet', 'my'].includes(s)) setTab(s === 'store2' ? 'store' : s);
  };
  const notify = t => {
    setToast(t);
    setTimeout(() => setToast(null), 2600);
  };
  const ctx = {
    cart,
    setCart,
    slot,
    setSlot,
    go,
    notify
  };
  const body = screen === 'store' ? /*#__PURE__*/React.createElement(StoreScreen, ctx) : screen === 'checkout' ? /*#__PURE__*/React.createElement(CheckoutScreen, ctx) : screen === 'order' ? /*#__PURE__*/React.createElement(OrderScreen, ctx) : screen === 'wallet' ? /*#__PURE__*/React.createElement(WalletScreen, ctx) : /*#__PURE__*/React.createElement(HomeScreen, ctx);
  const showTabs = ['home', 'order', 'wallet'].includes(screen);
  return /*#__PURE__*/React.createElement("div", {
    className: "bf-app"
  }, body, showTabs && /*#__PURE__*/React.createElement(TabBar, {
    value: tab,
    onChange: v => {
      setTab(v);
      go(v === 'store' ? 'store' : v);
    },
    items: [{
      value: 'home',
      label: '홈',
      icon: 'house'
    }, {
      value: 'store',
      label: '매장',
      icon: 'store'
    }, {
      value: 'order',
      label: '주문',
      icon: 'receipt'
    }, {
      value: 'wallet',
      label: '지갑',
      icon: 'wallet'
    }, {
      value: 'my',
      label: '마이',
      icon: 'user'
    }]
  }), toast && /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 16,
      right: 16,
      bottom: 84,
      zIndex: 20
    }
  }, /*#__PURE__*/React.createElement(Toast, {
    title: toast.title,
    icon: toast.icon,
    tone: toast.tone
  }, toast.body)));
}
Object.assign(window, {
  CustomerApp
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/customer_app/App.jsx", error: String((e && e.message) || e) }); }

// ui_kits/customer_app/CheckoutScreen.jsx
try { (() => {
const {
  TopBar,
  Card,
  Radio,
  Checkbox,
  Button,
  CouponCard,
  Icon,
  ListRow,
  Alert,
  Dialog,
  QuantityStepper,
  Badge
} = window.BeanFlowDesignSystem_c0ae52;
function CheckoutScreen({
  cart,
  setCart,
  slot,
  go,
  notify
}) {
  const [pay, setPay] = React.useState('wallet');
  const [useCoupon, setUseCoupon] = React.useState(true);
  const [usePoint, setUsePoint] = React.useState(false);
  const [confirm, setConfirm] = React.useState(false);
  const lines = cart.length ? cart : [{
    name: '아이스 아메리카노',
    price: 4500,
    qty: 2,
    description: 'ICE · 샷추가'
  }, {
    name: '오트 라떼',
    price: 5800,
    qty: 1,
    description: 'HOT'
  }];
  const sub = lines.reduce((s, i) => s + i.price * i.qty, 0);
  const discount = (useCoupon ? 2000 : 0) + (usePoint ? 1200 : 0);
  const total = Math.max(0, sub - discount);
  const won = n => n.toLocaleString('ko-KR') + '원';
  return /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(TopBar, {
    back: true,
    onBack: () => go('store'),
    title: "\uACB0\uC81C"
  }), /*#__PURE__*/React.createElement("div", {
    className: "bf-app__scroll"
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-app__pad",
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 18
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padded: true
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      marginBottom: 12
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "clock",
    size: 18,
    style: {
      color: 'var(--caramel-600)'
    }
  }), /*#__PURE__*/React.createElement("b", {
    style: {
      color: 'var(--text-strong)'
    }
  }, "\uAC15\uB0A8 2\uD638\uC810 \xB7 ", slot, " \uD53D\uC5C5"), /*#__PURE__*/React.createElement(Button, {
    variant: "ghost",
    size: "sm",
    style: {
      marginLeft: 'auto'
    },
    onClick: () => go('store')
  }, "\uBCC0\uACBD")), lines.map((l, i) => /*#__PURE__*/React.createElement("div", {
    key: i,
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 12,
      padding: '10px 0',
      borderTop: '1px solid var(--divider)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontWeight: 600,
      color: 'var(--text-strong)',
      fontSize: 14
    }
  }, l.name), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12,
      color: 'var(--text-muted)'
    }
  }, l.description)), /*#__PURE__*/React.createElement(QuantityStepper, {
    value: l.qty,
    onChange: n => setCart(c => c.map((x, xi) => xi === i ? {
      ...x,
      qty: n
    } : x))
  }), /*#__PURE__*/React.createElement("span", {
    className: "bf-num",
    style: {
      fontWeight: 600,
      color: 'var(--text-strong)',
      width: 74,
      textAlign: 'right'
    }
  }, won(l.price * l.qty))))), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      fontWeight: 700,
      color: 'var(--text-strong)',
      marginBottom: 10
    }
  }, "\uD560\uC778"), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(CouponCard, {
    amount: 2000,
    name: "\uC810\uC2EC \uD55C\uC815 \uCFE0\uD3F0",
    condition: "8,000\uC6D0 \uC774\uC0C1 \xB7 11\u201314\uC2DC",
    expiry: "\uC624\uB298 23:59 \uB9CC\uB8CC",
    expiringSoon: true,
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: useCoupon ? 'primary' : 'secondary',
      onClick: () => setUseCoupon(v => !v)
    }, useCoupon ? '적용됨' : '적용')
  }), /*#__PURE__*/React.createElement(Card, {
    padded: true
  }, /*#__PURE__*/React.createElement(Checkbox, {
    checked: usePoint,
    onChange: e => setUsePoint(e.target.checked),
    label: "\uD3EC\uC778\uD2B8 1,200P \uC0AC\uC6A9",
    description: "\uBCF4\uC720 11,240P \xB7 11,200P\uB294 8/31 \uB9CC\uB8CC"
  })))), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      fontWeight: 700,
      color: 'var(--text-strong)',
      marginBottom: 10
    }
  }, "\uACB0\uC81C\uC218\uB2E8"), /*#__PURE__*/React.createElement(Card, null, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '14px 16px',
      borderBottom: '1px solid var(--divider)'
    }
  }, /*#__PURE__*/React.createElement(Radio, {
    name: "pay",
    checked: pay === 'wallet',
    onChange: () => setPay('wallet'),
    label: "BeanFlow \uC9C0\uAC11",
    description: "\uC794\uC561 24,000\uC6D0 \xB7 \uACB0\uC81C \uC2DC 1% \uCD94\uAC00 \uC801\uB9BD"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '14px 16px',
      borderBottom: '1px solid var(--divider)'
    }
  }, /*#__PURE__*/React.createElement(Radio, {
    name: "pay",
    checked: pay === 'card',
    onChange: () => setPay('card'),
    label: "\uC2E0\uD55C\uCE74\uB4DC \u2022\u2022\u2022\u20224821",
    description: "\uAE30\uBCF8 \uACB0\uC81C\uC218\uB2E8"
  })), /*#__PURE__*/React.createElement(ListRow, {
    leading: /*#__PURE__*/React.createElement(Icon, {
      name: "plus"
    }),
    title: "\uACB0\uC81C\uC218\uB2E8 \uCD94\uAC00",
    chevron: true,
    onClick: () => {}
  }))), /*#__PURE__*/React.createElement(Alert, {
    tone: "info"
  }, "\uB3D9\uC77C \uB9E4\uC7A5\xB7\uB3D9\uC77C \uAE08\uC561 \uC8FC\uBB38\uC774 60\uCD08 \uB0B4 \uC911\uBCF5 \uC694\uCCAD\uB418\uBA74 \uC790\uB3D9\uC73C\uB85C \uD55C \uAC74\uB9CC \uACB0\uC81C\uB3FC\uC694."), /*#__PURE__*/React.createElement(Card, {
    padded: true
  }, [['주문 금액', won(sub)], ['쿠폰 할인', useCoupon ? '−' + won(2000) : '−0원'], ['포인트 사용', usePoint ? '−' + won(1200) : '−0원']].map(([k, v]) => /*#__PURE__*/React.createElement("div", {
    key: k,
    style: {
      display: 'flex',
      justifyContent: 'space-between',
      fontSize: 14,
      color: 'var(--text-muted)',
      padding: '4px 0'
    }
  }, /*#__PURE__*/React.createElement("span", null, k), /*#__PURE__*/React.createElement("span", {
    className: "bf-num",
    style: {
      color: k === '주문 금액' ? 'var(--text-body)' : 'var(--berry-600)'
    }
  }, v))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'baseline',
      marginTop: 10,
      paddingTop: 12,
      borderTop: '1px solid var(--divider)'
    }
  }, /*#__PURE__*/React.createElement("b", {
    style: {
      color: 'var(--text-strong)'
    }
  }, "\uACB0\uC81C \uAE08\uC561"), /*#__PURE__*/React.createElement("span", {
    className: "bf-num",
    style: {
      fontSize: 22,
      fontWeight: 800,
      color: 'var(--text-strong)',
      letterSpacing: 'var(--ls-title)'
    }
  }, won(total))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      textAlign: 'right'
    }
  }, /*#__PURE__*/React.createElement(Badge, {
    tone: "accent"
  }, Math.round(total * 0.03).toLocaleString('ko-KR'), "P \uC801\uB9BD \uC608\uC815"))))), /*#__PURE__*/React.createElement("div", {
    className: "bf-cta"
  }, /*#__PURE__*/React.createElement(Button, {
    size: "xl",
    block: true,
    iconLeft: "credit-card",
    onClick: () => setConfirm(true)
  }, won(total), " \uACB0\uC81C\uD558\uAE30")), /*#__PURE__*/React.createElement(Dialog, {
    open: confirm,
    sheet: true,
    title: `${won(total)}을 결제할까요?`,
    description: `${slot} 픽업으로 강남 2호점에 주문이 접수됩니다. 매장이 수락하기 전까지 무료로 취소할 수 있어요.`,
    onClose: () => setConfirm(false),
    actions: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(Button, {
      variant: "secondary",
      block: true,
      onClick: () => setConfirm(false)
    }, "\uB2EB\uAE30"), /*#__PURE__*/React.createElement(Button, {
      block: true,
      onClick: () => {
        setConfirm(false);
        go('order');
        notify({
          title: '주문이 접수됐어요',
          icon: 'check',
          body: 'A-142 · ' + slot + ' 픽업'
        });
      }
    }, "\uACB0\uC81C\uD558\uAE30"))
  }));
}
Object.assign(window, {
  CheckoutScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/customer_app/CheckoutScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/customer_app/HomeScreen.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const {
  SearchField,
  SectionHeader,
  StoreCard,
  Card,
  Badge,
  Button,
  Icon,
  OrderStatus,
  CouponCard
} = window.BeanFlowDesignSystem_c0ae52;
const STORES = [{
  name: 'BeanFlow 강남 2호점',
  distance: '320m',
  walkMinutes: 4,
  waitMinutes: 6,
  tags: ['적립 2배']
}, {
  name: 'BeanFlow 역삼 스퀘어점',
  distance: '540m',
  walkMinutes: 7,
  waitMinutes: 11,
  tags: ['점심 쿠폰']
}, {
  name: 'BeanFlow 선릉 1호점',
  distance: '870m',
  walkMinutes: 11,
  waitMinutes: 3,
  tags: []
}];
const RECENT = [{
  name: '아이스 아메리카노',
  opt: 'ICE · 샷추가',
  price: '5,000원'
}, {
  name: '오트 라떼',
  opt: 'HOT · 시럽 빼기',
  price: '5,800원'
}];
function HomeScreen({
  go,
  notify
}) {
  const [q, setQ] = React.useState('');
  return /*#__PURE__*/React.createElement("div", {
    className: "bf-app__scroll"
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px var(--gutter-mobile) 0'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: "../../assets/logo-mark.png",
    alt: "",
    style: {
      height: 26
    }
  }), /*#__PURE__*/React.createElement("button", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 4,
      border: 0,
      background: 'transparent',
      padding: 0,
      cursor: 'pointer',
      fontWeight: 600,
      color: 'var(--text-strong)',
      fontSize: 15
    }
  }, "\uD14C\uD5E4\uB780\uB85C 231 ", /*#__PURE__*/React.createElement(Icon, {
    name: "chevron-down",
    size: 16
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginLeft: 'auto',
      display: 'flex',
      gap: 4
    }
  }, /*#__PURE__*/React.createElement("button", {
    className: "bf-iconbtn bf-iconbtn--md",
    "aria-label": "\uC54C\uB9BC"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "bell",
    size: 20
  })))), /*#__PURE__*/React.createElement("h1", {
    style: {
      fontSize: 'var(--fs-title-1)',
      letterSpacing: 'var(--ls-title)',
      marginTop: 14
    }
  }, "\uC904 \uC11C\uC9C0 \uC54A\uB294 \uC810\uC2EC,", /*#__PURE__*/React.createElement("br", null), "11\uBD84 \uB4A4\uC5D0 \uC900\uBE44\uB3FC\uC694"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14
    }
  }, /*#__PURE__*/React.createElement(SearchField, {
    value: q,
    onChange: e => setQ(e.target.value),
    onClear: () => setQ(''),
    placeholder: "\uB9E4\uC7A5\xB7\uBA54\uB274 \uAC80\uC0C9"
  }))), /*#__PURE__*/React.createElement("div", {
    className: "bf-app__pad",
    style: {
      paddingTop: 22
    }
  }, /*#__PURE__*/React.createElement(Card, {
    variant: "raised",
    padded: true,
    style: {
      marginBottom: 22,
      cursor: 'pointer'
    },
    onClick: () => go('order')
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(OrderStatus, {
    status: "making"
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: 'auto',
      fontSize: 'var(--fs-caption)',
      color: 'var(--text-muted)'
    }
  }, "\uD53D\uC5C5 12:20")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 12,
      marginTop: 12
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-mono",
    style: {
      fontSize: 26,
      fontWeight: 600,
      color: 'var(--espresso-700)'
    }
  }, "A-142"), /*#__PURE__*/React.createElement("div", {
    style: {
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontWeight: 600,
      color: 'var(--text-strong)',
      fontSize: 14
    }
  }, "\uC544\uC774\uC2A4 \uC544\uBA54\uB9AC\uCE74\uB178 \uC678 1\uC794"), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 13,
      color: 'var(--text-muted)'
    }
  }, "\uAC15\uB0A8 2\uD638\uC810 \xB7 \uB3C4\uBCF4 4\uBD84")), /*#__PURE__*/React.createElement(Icon, {
    name: "chevron-right",
    size: 18,
    style: {
      marginLeft: 'auto',
      color: 'var(--text-faint)'
    }
  }))), /*#__PURE__*/React.createElement(SectionHeader, {
    title: "\uBE60\uB978 \uC7AC\uC8FC\uBB38",
    description: "\uC9C0\uB09C \uC8FC\uBB38 \uADF8\uB300\uB85C \uD55C \uBC88\uC5D0"
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 10,
      overflowX: 'auto',
      paddingBottom: 4,
      marginBottom: 24
    }
  }, RECENT.map(r => /*#__PURE__*/React.createElement(Card, {
    key: r.name,
    padded: true,
    interactive: true,
    style: {
      minWidth: 196,
      flex: 'none'
    },
    onClick: () => notify({
      title: '장바구니에 담았어요',
      icon: 'shopping-bag',
      body: r.name
    })
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontWeight: 600,
      color: 'var(--text-strong)',
      fontSize: 14
    }
  }, r.name), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12,
      color: 'var(--text-muted)',
      marginTop: 2
    }
  }, r.opt), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      marginTop: 12
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "bf-num",
    style: {
      fontWeight: 700,
      color: 'var(--text-strong)'
    }
  }, r.price), /*#__PURE__*/React.createElement(Button, {
    size: "sm",
    variant: "secondary",
    iconLeft: "rotate-ccw",
    style: {
      marginLeft: 'auto'
    }
  }, "\uC7AC\uC8FC\uBB38"))))), /*#__PURE__*/React.createElement(SectionHeader, {
    title: "\uAC00\uAE4C\uC6B4 \uB9E4\uC7A5",
    action: /*#__PURE__*/React.createElement(Button, {
      variant: "ghost",
      size: "sm",
      iconRight: "chevron-right"
    }, "\uC9C0\uB3C4")
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 10,
      marginBottom: 24
    }
  }, STORES.map(s => /*#__PURE__*/React.createElement(StoreCard, _extends({
    key: s.name
  }, s, {
    onClick: () => go('store')
  })))), /*#__PURE__*/React.createElement(SectionHeader, {
    title: "\uC624\uB298\uC758 \uCFE0\uD3F0",
    eyebrow: "\uD55C\uC815 \uC218\uB7C9"
  }), /*#__PURE__*/React.createElement(CouponCard, {
    amount: 2000,
    name: "\uC810\uC2EC \uD55C\uC815 \uCFE0\uD3F0",
    condition: "8,000\uC6D0 \uC774\uC0C1 \xB7 11\u201314\uC2DC",
    expiry: "\uC624\uB298 23:59 \uB9CC\uB8CC",
    expiringSoon: true,
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      onClick: () => notify({
        title: '쿠폰을 받았어요',
        icon: 'ticket',
        tone: 'accent',
        body: '결제 시 자동으로 적용됩니다.'
      })
    }, "\uBC1B\uAE30")
  })));
}
Object.assign(window, {
  HomeScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/customer_app/HomeScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/customer_app/OrderScreen.jsx
try { (() => {
const {
  TopBar,
  Card,
  OrderStatus,
  Button,
  Icon,
  Tabs,
  Badge,
  ListRow,
  Alert
} = window.BeanFlowDesignSystem_c0ae52;
const STEPS = [{
  key: 'placed',
  label: '주문 접수',
  time: '12:04'
}, {
  key: 'making',
  label: '제조 중',
  time: '12:14'
}, {
  key: 'ready',
  label: '준비 완료',
  time: '12:20 예정'
}, {
  key: 'picked',
  label: '픽업 완료',
  time: ''
}];
function OrderScreen({
  go,
  notify
}) {
  const [tab, setTab] = React.useState('active');
  const [stage, setStage] = React.useState(1);
  return /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(TopBar, {
    title: "\uC8FC\uBB38",
    actions: /*#__PURE__*/React.createElement(Button, {
      variant: "ghost",
      size: "sm",
      iconLeft: "headset"
    }, "\uBB38\uC758")
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '0 var(--gutter-mobile)',
      background: 'var(--surface-card)',
      borderBottom: '1px solid var(--divider)'
    }
  }, /*#__PURE__*/React.createElement(Tabs, {
    value: tab,
    onChange: setTab,
    items: [{
      value: 'active',
      label: '진행 중',
      count: 1
    }, {
      value: 'past',
      label: '지난 주문',
      count: 24
    }]
  })), /*#__PURE__*/React.createElement("div", {
    className: "bf-app__scroll"
  }, tab === 'active' ? /*#__PURE__*/React.createElement("div", {
    className: "bf-app__pad",
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 16
    }
  }, /*#__PURE__*/React.createElement(Card, {
    variant: "raised",
    padded: true
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center'
    }
  }, /*#__PURE__*/React.createElement(OrderStatus, {
    status: STEPS[stage].key
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: 'auto',
      fontSize: 13,
      color: 'var(--text-muted)'
    }
  }, "\uAC15\uB0A8 2\uD638\uC810")), /*#__PURE__*/React.createElement("div", {
    className: "bf-mono",
    style: {
      fontSize: 44,
      fontWeight: 600,
      color: 'var(--espresso-700)',
      letterSpacing: '-0.02em',
      marginTop: 10
    }
  }, "A-142"), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 14,
      color: 'var(--text-muted)'
    }
  }, "\uD53D\uC5C5\uB300\uC5D0\uC11C \uBC88\uD638\uB97C \uD655\uC778\uD574 \uC8FC\uC138\uC694"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 20,
      display: 'flex',
      flexDirection: 'column',
      gap: 0
    }
  }, STEPS.map((s, i) => /*#__PURE__*/React.createElement("div", {
    key: s.key,
    style: {
      display: 'flex',
      gap: 12,
      alignItems: 'flex-start'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      width: 20
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 12,
      height: 12,
      borderRadius: 999,
      background: i <= stage ? 'var(--caramel-500)' : 'var(--crema-300)',
      border: i === stage ? '3px solid var(--caramel-100)' : 'none'
    }
  }), i < STEPS.length - 1 && /*#__PURE__*/React.createElement("span", {
    style: {
      width: 2,
      height: 26,
      background: i < stage ? 'var(--caramel-400)' : 'var(--crema-200)'
    }
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      paddingBottom: 12
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 14,
      fontWeight: i === stage ? 700 : 500,
      color: i <= stage ? 'var(--text-strong)' : 'var(--text-faint)'
    }
  }, s.label), s.time && /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12,
      color: 'var(--text-faint)'
    }
  }, s.time))))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8,
      marginTop: 4
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "secondary",
    block: true,
    iconLeft: "map-pin"
  }, "\uAE38\uCC3E\uAE30"), stage < 2 ? /*#__PURE__*/React.createElement(Button, {
    block: true,
    onClick: () => {
      setStage(2);
      notify({
        title: '준비 완료!',
        icon: 'coffee',
        body: 'A-142 주문을 픽업대에서 받아가세요.'
      });
    }
  }, "\uC900\uBE44 \uC644\uB8CC \uC54C\uB9BC \uD14C\uC2A4\uD2B8") : /*#__PURE__*/React.createElement(Button, {
    block: true,
    variant: "accent",
    onClick: () => setStage(3)
  }, "\uD53D\uC5C5 \uC644\uB8CC"))), /*#__PURE__*/React.createElement(Card, {
    padded: true
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontWeight: 700,
      color: 'var(--text-strong)',
      marginBottom: 10
    }
  }, "\uC8FC\uBB38 \uB0B4\uC5ED"), [['아이스 아메리카노 (ICE · 샷추가)', 2, '9,000원'], ['오트 라떼 (HOT)', 1, '5,800원']].map(([n, q, p]) => /*#__PURE__*/React.createElement("div", {
    key: n,
    style: {
      display: 'flex',
      gap: 10,
      padding: '6px 0',
      fontSize: 14
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      color: 'var(--caramel-600)',
      fontWeight: 700
    }
  }, q), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1,
      color: 'var(--text-body)'
    }
  }, n), /*#__PURE__*/React.createElement("span", {
    className: "bf-num",
    style: {
      color: 'var(--text-strong)',
      fontWeight: 500
    }
  }, p))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      justifyContent: 'space-between',
      marginTop: 10,
      paddingTop: 10,
      borderTop: '1px solid var(--divider)'
    }
  }, /*#__PURE__*/React.createElement("b", {
    style: {
      color: 'var(--text-strong)'
    }
  }, "\uACB0\uC81C \uAE08\uC561"), /*#__PURE__*/React.createElement("span", {
    className: "bf-num",
    style: {
      fontWeight: 700,
      color: 'var(--text-strong)'
    }
  }, "12,800\uC6D0"))), /*#__PURE__*/React.createElement(Alert, {
    tone: "warning",
    title: "\uBD80\uBD84 \uCDE8\uC18C\uB294 \uC2AC\uB86F \uB9C8\uAC10 5\uBD84 \uC804\uAE4C\uC9C0",
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "ghost"
    }, "\uCDE8\uC18C \uC694\uCCAD")
  }, "\uC81C\uC870\uAC00 \uC2DC\uC791\uB41C \uC794\uC740 \uCDE8\uC18C\uB418\uC9C0 \uC54A\uC544\uC694.")) : /*#__PURE__*/React.createElement("div", {
    className: "bf-app__pad",
    style: {
      padding: 0
    }
  }, [['07-25', 'A-118', '아이스 아메리카노 외 1잔', '10,300원', 'picked'], ['07-24', 'A-092', '콜드브루', '5,500원', 'picked'], ['07-23', 'A-077', '오트 라떼 외 2잔', '17,400원', 'refund'], ['07-22', 'A-061', '카페 라떼', '5,300원', 'canceled']].map(([d, no, item, amt, st]) => /*#__PURE__*/React.createElement(ListRow, {
    key: no,
    title: item,
    subtitle: `${d} · ${no} · 강남 2호점`,
    onClick: () => {},
    chevron: true,
    trailing: /*#__PURE__*/React.createElement("div", {
      style: {
        textAlign: 'right'
      }
    }, /*#__PURE__*/React.createElement("div", {
      className: "bf-num",
      style: {
        fontWeight: 600,
        color: 'var(--text-strong)'
      }
    }, amt), /*#__PURE__*/React.createElement("div", {
      style: {
        marginTop: 4
      }
    }, /*#__PURE__*/React.createElement(OrderStatus, {
      status: st
    })))
  })))));
}
Object.assign(window, {
  OrderScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/customer_app/OrderScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/customer_app/StoreScreen.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const {
  TopBar,
  IconButton,
  Tabs,
  MenuItem,
  PickupSlots,
  Button,
  Badge,
  Icon,
  Alert
} = window.BeanFlowDesignSystem_c0ae52;
const MENU = {
  coffee: [{
    name: '아이스 아메리카노',
    description: '산미 낮은 하우스 블렌드',
    price: 4500,
    originalPrice: 5000,
    badge: '타임세일'
  }, {
    name: '카페 라떼',
    description: '고소한 우유 비율 7:3',
    price: 5300
  }, {
    name: '오트 라떼',
    description: '오늘 재고 3잔',
    price: 5800
  }, {
    name: '콜드브루',
    description: '18시간 저온 추출',
    price: 5500,
    soldOut: true
  }],
  tea: [{
    name: '자몽 허니 블랙티',
    description: '생과일 자몽 슬라이스',
    price: 6000
  }, {
    name: '캐모마일',
    description: '카페인 없음',
    price: 4800
  }],
  dessert: [{
    name: '버터 크루아상',
    description: '매일 아침 구움',
    price: 4200
  }, {
    name: '바스크 치즈케이크',
    description: '2조각 남음',
    price: 6500,
    badge: '인기'
  }]
};
function StoreScreen({
  cart,
  setCart,
  slot,
  setSlot,
  go,
  notify
}) {
  const [cat, setCat] = React.useState('coffee');
  const total = cart.reduce((s, i) => s + i.price * i.qty, 0);
  const add = m => {
    setCart(c => {
      const hit = c.find(i => i.name === m.name);
      return hit ? c.map(i => i.name === m.name ? {
        ...i,
        qty: i.qty + 1
      } : i) : [...c, {
        ...m,
        qty: 1
      }];
    });
    notify({
      title: '담았어요',
      icon: 'shopping-bag',
      body: m.name
    });
  };
  return /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(TopBar, {
    back: true,
    onBack: () => go('home'),
    title: "\uAC15\uB0A8 2\uD638\uC810",
    actions: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(IconButton, {
      icon: "heart",
      label: "\uCC1C"
    }), /*#__PURE__*/React.createElement(IconButton, {
      icon: "share-2",
      label: "\uACF5\uC720"
    }))
  }), /*#__PURE__*/React.createElement("div", {
    className: "bf-app__scroll"
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      height: 152,
      background: 'linear-gradient(135deg,var(--espresso-600),var(--espresso-800))',
      display: 'flex',
      alignItems: 'flex-end',
      padding: 16,
      color: '#fff',
      position: 'relative'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      right: -30,
      top: -50,
      width: 180,
      height: 180,
      borderRadius: 999,
      background: 'radial-gradient(circle,rgba(245,168,90,.3),transparent 68%)'
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'relative'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 6,
      marginBottom: 8
    }
  }, /*#__PURE__*/React.createElement(Badge, {
    tone: "accent"
  }, "\uC801\uB9BD 2\uBC30"), /*#__PURE__*/React.createElement(Badge, {
    tone: "success",
    dot: true
  }, "\uC601\uC5C5 \uC911")), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 13,
      color: 'var(--crema-300)'
    }
  }, "\uC11C\uC6B8 \uAC15\uB0A8\uAD6C \uD14C\uD5E4\uB780\uB85C 231 \xB7 \uB3C4\uBCF4 4\uBD84 \xB7 \uC608\uC0C1 \uB300\uAE30 6\uBD84"))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '16px var(--gutter-mobile) 0'
    }
  }, /*#__PURE__*/React.createElement(Alert, {
    tone: "info",
    title: "\uC810\uC2EC \uC2AC\uB86F \uC6B4\uC601 \uC911"
  }, "11\u201314\uC2DC\uC5D0\uB294 10\uBD84 \uB2E8\uC704\uB85C \uC794 \uC218\uAC00 \uC81C\uD55C\uB3FC\uC694. \uC2AC\uB86F\uC744 \uBA3C\uC800 \uACE0\uB974\uBA74 \uADF8 \uC2DC\uAC04\uC5D0 \uB9DE\uCDB0 \uC81C\uC870\uD569\uB2C8\uB2E4."), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 18,
      marginBottom: 8,
      fontWeight: 700,
      color: 'var(--text-strong)',
      fontSize: 'var(--fs-title-3)'
    }
  }, "\uD53D\uC5C5 \uC2DC\uAC04"), /*#__PURE__*/React.createElement(PickupSlots, {
    value: slot,
    onChange: setSlot,
    slots: [{
      time: '12:00',
      remaining: 8
    }, {
      time: '12:10',
      remaining: 5
    }, {
      time: '12:20',
      remaining: 2
    }, {
      time: '12:30',
      remaining: 0
    }, {
      time: '12:40',
      remaining: 9
    }, {
      time: '12:50',
      remaining: 12
    }]
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 20,
      position: 'sticky',
      top: 0,
      background: 'var(--surface-app)',
      zIndex: 2,
      padding: '0 var(--gutter-mobile)'
    }
  }, /*#__PURE__*/React.createElement(Tabs, {
    value: cat,
    onChange: setCat,
    items: [{
      value: 'coffee',
      label: '커피'
    }, {
      value: 'tea',
      label: '티'
    }, {
      value: 'dessert',
      label: '디저트'
    }]
  })), /*#__PURE__*/React.createElement("div", {
    className: "bf-card",
    style: {
      margin: '0 var(--gutter-mobile) 20px',
      borderRadius: 0,
      border: 0,
      background: 'transparent'
    }
  }, MENU[cat].map(m => /*#__PURE__*/React.createElement(MenuItem, _extends({
    key: m.name
  }, m, {
    onClick: () => add(m),
    style: {
      padding: '12px 0'
    },
    trailing: !m.soldOut && /*#__PURE__*/React.createElement("span", {
      className: "bf-iconbtn bf-iconbtn--sm bf-iconbtn--outline"
    }, /*#__PURE__*/React.createElement(Icon, {
      name: "plus",
      size: 16
    }))
  }))))), /*#__PURE__*/React.createElement("div", {
    className: "bf-cta"
  }, /*#__PURE__*/React.createElement(Button, {
    size: "xl",
    block: true,
    disabled: cart.length === 0,
    onClick: () => go('checkout'),
    iconLeft: "shopping-bag"
  }, cart.length === 0 ? '메뉴를 담아주세요' : `${cart.reduce((s, i) => s + i.qty, 0)}잔 · ${total.toLocaleString('ko-KR')}원 주문하기`)));
}
Object.assign(window, {
  StoreScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/customer_app/StoreScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/customer_app/WalletScreen.jsx
try { (() => {
const {
  TopBar,
  BalanceCard,
  Button,
  Card,
  Tabs,
  CouponCard,
  ListRow,
  Icon,
  ProgressBar,
  Switch,
  EmptyState,
  Badge
} = window.BeanFlowDesignSystem_c0ae52;
function WalletScreen({
  notify
}) {
  const [tab, setTab] = React.useState('wallet');
  return /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(TopBar, {
    title: "\uC9C0\uAC11",
    actions: /*#__PURE__*/React.createElement(Button, {
      variant: "ghost",
      size: "sm",
      iconLeft: "settings"
    }, "\uC124\uC815")
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '0 var(--gutter-mobile)',
      background: 'var(--surface-card)',
      borderBottom: '1px solid var(--divider)'
    }
  }, /*#__PURE__*/React.createElement(Tabs, {
    value: tab,
    onChange: setTab,
    items: [{
      value: 'wallet',
      label: '선불 지갑'
    }, {
      value: 'point',
      label: '포인트'
    }, {
      value: 'coupon',
      label: '쿠폰',
      count: 3
    }]
  })), /*#__PURE__*/React.createElement("div", {
    className: "bf-app__scroll"
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-app__pad",
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 16
    }
  }, tab === 'wallet' && /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(BalanceCard, {
    kind: "wallet",
    value: 24000,
    caption: "\uC790\uB3D9\uCDA9\uC804 \uCF1C\uC9D0 \xB7 5,000\uC6D0 \uC774\uD558 \uC2DC 30,000\uC6D0",
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "secondary",
      onClick: () => notify({
        title: '30,000원 충전 완료',
        icon: 'wallet',
        tone: 'accent'
      })
    }, "\uCDA9\uC804")
  }), /*#__PURE__*/React.createElement(Card, null, /*#__PURE__*/React.createElement(ListRow, {
    leading: /*#__PURE__*/React.createElement(Icon, {
      name: "zap"
    }),
    title: "\uC790\uB3D9 \uCDA9\uC804",
    subtitle: "\uC794\uC561 5,000\uC6D0 \uC774\uD558\uC77C \uB54C",
    trailing: /*#__PURE__*/React.createElement(Switch, {
      defaultChecked: true
    })
  }), /*#__PURE__*/React.createElement(ListRow, {
    leading: /*#__PURE__*/React.createElement(Icon, {
      name: "credit-card"
    }),
    title: "\uCDA9\uC804 \uCE74\uB4DC",
    subtitle: "\uC2E0\uD55C\uCE74\uB4DC \u2022\u2022\u2022\u20224821",
    chevron: true,
    onClick: () => {}
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      fontWeight: 700,
      color: 'var(--text-strong)'
    }
  }, "\uCDA9\uC804\xB7\uC0AC\uC6A9 \uB0B4\uC5ED"), /*#__PURE__*/React.createElement(Card, null, [['07-25', '강남 2호점 결제', '−12,800원', 'berry'], ['07-24', '자동 충전', '+30,000원', 'mint'], ['07-23', '부분 환불 (오트 라떼)', '+5,800원', 'mint'], ['07-22', '역삼 스퀘어점 결제', '−5,300원', 'berry']].map(([d, t, a, c]) => /*#__PURE__*/React.createElement(ListRow, {
    key: d + t,
    title: t,
    subtitle: d,
    trailing: /*#__PURE__*/React.createElement("span", {
      className: "bf-num",
      style: {
        fontWeight: 600,
        color: c === 'mint' ? 'var(--mint-600)' : 'var(--text-strong)'
      }
    }, a)
  })))), tab === 'point' && /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(BalanceCard, {
    value: 11240,
    caption: "11,200P\uB294 8/31 \uB9CC\uB8CC",
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "secondary"
    }, "\uC0AC\uC6A9\uCC98")
  }), /*#__PURE__*/React.createElement(Card, {
    padded: true
  }, /*#__PURE__*/React.createElement(ProgressBar, {
    label: "\uBB34\uB8CC \uC74C\uB8CC\uAE4C\uC9C0",
    valueLabel: "7 / 10\uC794",
    value: 7,
    max: 10
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 13,
      color: 'var(--text-muted)',
      marginTop: 10
    }
  }, "3\uC794 \uB354 \uB9C8\uC2DC\uBA74 \uC544\uBA54\uB9AC\uCE74\uB178 1\uC794\uC774 \uBB34\uB8CC\uC608\uC694.")), /*#__PURE__*/React.createElement(Card, null, [['07-25', '결제 적립 (3%)', '+384P'], ['07-24', '리뷰 적립', '+200P'], ['07-20', '포인트 사용', '−1,200P'], ['06-30', '유효기간 만료', '−540P']].map(([d, t, a]) => /*#__PURE__*/React.createElement(ListRow, {
    key: d + t,
    title: t,
    subtitle: d,
    trailing: /*#__PURE__*/React.createElement("span", {
      className: "bf-num",
      style: {
        fontWeight: 600,
        color: a.startsWith('+') ? 'var(--caramel-600)' : 'var(--text-muted)'
      }
    }, a)
  })))), tab === 'coupon' && /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(CouponCard, {
    amount: 2000,
    name: "\uC810\uC2EC \uD55C\uC815 \uCFE0\uD3F0",
    condition: "8,000\uC6D0 \uC774\uC0C1 \xB7 11\u201314\uC2DC",
    expiry: "\uC624\uB298 23:59 \uB9CC\uB8CC",
    expiringSoon: true,
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm"
    }, "\uC0AC\uC6A9")
  }), /*#__PURE__*/React.createElement(CouponCard, {
    amount: "30",
    amountUnit: "%",
    name: "\uC2E0\uADDC \uAC00\uC785 \uCD95\uD558",
    condition: "\uCCAB \uC8FC\uBB38 \xB7 \uCD5C\uB300 3,000\uC6D0",
    expiry: "08-15\uAE4C\uC9C0",
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "secondary"
    }, "\uC0AC\uC6A9")
  }), /*#__PURE__*/React.createElement(CouponCard, {
    amount: 1000,
    name: "\uCE5C\uAD6C \uCD08\uB300 \uB9AC\uC6CC\uB4DC",
    condition: "\uBAA8\uB4E0 \uB9E4\uC7A5",
    expiry: "09-01\uAE4C\uC9C0",
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "secondary"
    }, "\uC0AC\uC6A9")
  }), /*#__PURE__*/React.createElement(CouponCard, {
    amount: 2000,
    name: "6\uC6D4 \uC810\uC2EC \uCFE0\uD3F0",
    condition: "\uC0AC\uC6A9 \uC644\uB8CC \xB7 07-01",
    used: true
  })))));
}
Object.assign(window, {
  WalletScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/customer_app/WalletScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/merchant_console/ConsoleApp.jsx
try { (() => {
const {
  SideNav,
  TopBar,
  Select,
  Button,
  IconButton,
  Icon,
  Badge,
  Toast
} = window.BeanFlowDesignSystem_c0ae52;
const TITLES = {
  dashboard: '대시보드',
  pos: 'POS 주문보드',
  stock: '재고 관리',
  settle: '정산 · 이의제기'
};
function ConsoleApp() {
  const [view, setView] = React.useState('dashboard');
  const [toast, setToast] = React.useState(null);
  const notify = t => {
    setToast(t);
    setTimeout(() => setToast(null), 2600);
  };
  const Screen = {
    dashboard: DashboardScreen,
    pos: PosScreen,
    stock: StockScreen,
    settle: SettlementScreen
  }[view];
  return /*#__PURE__*/React.createElement("div", {
    className: "bf-console"
  }, /*#__PURE__*/React.createElement(SideNav, {
    value: view,
    onChange: setView,
    header: /*#__PURE__*/React.createElement("div", {
      style: {
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '2px 8px 14px'
      }
    }, /*#__PURE__*/React.createElement("img", {
      src: "../../assets/logo-mark.png",
      alt: "",
      style: {
        height: 24
      }
    }), /*#__PURE__*/React.createElement("img", {
      src: "../../assets/logo-wordmark.png",
      alt: "BeanFlow",
      style: {
        height: 15,
        filter: 'brightness(0) invert(1)'
      }
    }), /*#__PURE__*/React.createElement(Badge, {
      tone: "accent",
      style: {
        marginLeft: 'auto'
      }
    }, "\uC810\uC8FC")),
    groups: [{
      label: '운영',
      items: [{
        value: 'dashboard',
        label: '대시보드',
        icon: 'layout-dashboard'
      }, {
        value: 'pos',
        label: 'POS 주문보드',
        icon: 'monitor',
        badge: 6
      }, {
        value: 'stock',
        label: '재고 관리',
        icon: 'package'
      }]
    }, {
      label: '정산',
      items: [{
        value: 'settle',
        label: '정산 · 이의제기',
        icon: 'landmark',
        badge: 2
      }]
    }],
    footer: /*#__PURE__*/React.createElement("div", {
      style: {
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        padding: '10px 8px',
        borderTop: '1px solid rgba(255,255,255,.1)'
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        width: 30,
        height: 30,
        borderRadius: 999,
        background: 'var(--caramel-500)',
        color: 'var(--espresso-900)',
        display: 'grid',
        placeItems: 'center',
        fontWeight: 700,
        fontSize: 13
      }
    }, "\uAC15"), /*#__PURE__*/React.createElement("div", {
      style: {
        fontSize: 13,
        lineHeight: 1.3
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        color: '#fff',
        fontWeight: 600
      }
    }, "\uAC15\uB0A8 2\uD638\uC810"), /*#__PURE__*/React.createElement("div", {
      style: {
        color: 'var(--espresso-300)',
        fontSize: 11
      }
    }, "3\uAC1C \uB9E4\uC7A5 \uC6B4\uC601 \uC911")), /*#__PURE__*/React.createElement(Icon, {
      name: "chevrons-up-down",
      size: 16,
      style: {
        marginLeft: 'auto',
        color: 'var(--espresso-300)'
      }
    }))
  }), /*#__PURE__*/React.createElement("div", {
    className: "bf-console__main"
  }, /*#__PURE__*/React.createElement(TopBar, {
    title: TITLES[view],
    actions: /*#__PURE__*/React.createElement("div", {
      style: {
        display: 'flex',
        alignItems: 'center',
        gap: 10
      }
    }, /*#__PURE__*/React.createElement(Select, {
      options: [{
        value: 'gn2',
        label: '강남 2호점'
      }, {
        value: 'ys',
        label: '역삼 스퀘어점'
      }, {
        value: 'sr',
        label: '선릉 1호점'
      }],
      size: "sm"
    }), /*#__PURE__*/React.createElement(IconButton, {
      icon: "bell",
      label: "\uC54C\uB9BC",
      variant: "outline"
    }), /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "secondary",
      iconLeft: "download"
    }, "\uB0B4\uBCF4\uB0B4\uAE30"))
  }), /*#__PURE__*/React.createElement("div", {
    className: "bf-console__body"
  }, /*#__PURE__*/React.createElement(Screen, {
    notify: notify,
    go: setView
  }))), toast && /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'fixed',
      right: 24,
      bottom: 24,
      zIndex: 30
    }
  }, /*#__PURE__*/React.createElement(Toast, {
    title: toast.title,
    icon: toast.icon,
    tone: toast.tone
  }, toast.body)));
}
Object.assign(window, {
  ConsoleApp
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/merchant_console/ConsoleApp.jsx", error: String((e && e.message) || e) }); }

// ui_kits/merchant_console/DashboardScreen.jsx
try { (() => {
const {
  StatTile,
  Card,
  SectionHeader,
  Button,
  Badge,
  Icon,
  DataTable,
  OrderStatus,
  Alert,
  ProgressBar
} = window.BeanFlowDesignSystem_c0ae52;
const HOURS = [4, 6, 9, 14, 22, 38, 61, 74, 52, 33, 26, 31, 44, 29, 18, 9];
function Sparkbars() {
  const max = Math.max(...HOURS);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'flex-end',
      gap: 6,
      height: 148
    }
  }, HOURS.map((h, i) => /*#__PURE__*/React.createElement("div", {
    key: i,
    style: {
      flex: 1,
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      gap: 6
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: '100%',
      height: h / max * 124,
      borderRadius: '4px 4px 0 0',
      background: h === max ? 'var(--caramel-500)' : 'var(--espresso-200)'
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 10,
      color: 'var(--text-faint)',
      fontVariantNumeric: 'tabular-nums'
    }
  }, 7 + i))));
}
function DashboardScreen({
  notify,
  go
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 22
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-grid4"
  }, /*#__PURE__*/React.createElement(StatTile, {
    label: "\uC624\uB298 \uB9E4\uCD9C",
    value: 1284000,
    unit: "\uC6D0",
    delta: "+12.4%",
    trend: "up",
    caption: "\uC804\uC8FC \uB3D9\uC694\uC77C \uB300\uBE44"
  }), /*#__PURE__*/React.createElement(StatTile, {
    label: "\uC8FC\uBB38 \uAC74\uC218",
    value: 184,
    unit: "\uAC74",
    delta: "+8\uAC74",
    trend: "up",
    caption: "\uC804\uC8FC \uB300\uBE44"
  }), /*#__PURE__*/React.createElement(StatTile, {
    label: "\uAC1D\uB2E8\uAC00",
    value: 6420,
    unit: "\uC6D0",
    delta: "\u22122.1%",
    trend: "down",
    caption: "\uC804\uC8FC \uB300\uBE44"
  }), /*#__PURE__*/React.createElement(StatTile, {
    label: "\uD53D\uC5C5 \uB178\uC1FC",
    value: 3,
    unit: "\uAC74",
    delta: "0",
    trend: "flat",
    caption: "\uC804\uC8FC \uB300\uBE44"
  })), /*#__PURE__*/React.createElement(Card, {
    variant: "soft",
    padded: true
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 14
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 36,
      height: 36,
      flex: 'none',
      borderRadius: 10,
      background: 'var(--caramel-500)',
      color: 'var(--espresso-900)',
      display: 'grid',
      placeItems: 'center'
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "sparkles",
    size: 20
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("b", {
    style: {
      color: 'var(--espresso-800)'
    }
  }, "AI \uC778\uC0AC\uC774\uD2B8"), /*#__PURE__*/React.createElement(Badge, {
    tone: "accent"
  }, "\uC624\uB298 3\uAC74")), /*#__PURE__*/React.createElement("ul", {
    style: {
      margin: '10px 0 0',
      paddingLeft: 18,
      color: 'var(--espresso-700)',
      fontSize: 14,
      lineHeight: 1.75
    }
  }, /*#__PURE__*/React.createElement("li", null, "12:10\u201312:30 \uC2AC\uB86F\uC774 3\uC77C \uC5F0\uC18D \uB9C8\uAC10\uB410\uC5B4\uC694. \uC2AC\uB86F\uB2F9 \uC794 \uC218\uB97C 5 \u2192 7\uB85C \uC62C\uB9AC\uBA74 \uD558\uB8E8 \uC57D ", /*#__PURE__*/React.createElement("b", null, "62,000\uC6D0"), "\uC758 \uC774\uD0C8 \uB9E4\uCD9C\uC744 \uD68C\uC218\uD560 \uC218 \uC788\uC5B4\uC694."), /*#__PURE__*/React.createElement("li", null, "\uC624\uD2B8\uBC00\uD06C \uC18C\uC9C4 \uC18D\uB3C4\uAC00 \uC608\uCE21\uBCF4\uB2E4 22% \uBE68\uB77C\uC694. \uBAA9\uC694\uC77C \uBC1C\uC8FC\uB7C9\uC744 2\uBC15\uC2A4 \uB298\uB9AC\uB294 \uAC78 \uAD8C\uC7A5\uD574\uC694."), /*#__PURE__*/React.createElement("li", null, "\"\uC810\uC2EC \uD55C\uC815 \uCFE0\uD3F0\" \uC0AC\uC6A9\uB960 68% \u2014 \uC7AC\uBC1C\uAE09 \uC2DC \uC2E0\uADDC \uACE0\uAC1D \uC720\uC785\uC774 \uAC00\uC7A5 \uD070 \uC2DC\uAC04\uB300\uB294 11:20\uC785\uB2C8\uB2E4.")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8,
      marginTop: 14
    }
  }, /*#__PURE__*/React.createElement(Button, {
    size: "sm",
    onClick: () => notify({
      title: '슬롯 정원을 7잔으로 변경했어요',
      icon: 'check'
    })
  }, "\uC2AC\uB86F \uC815\uC6D0 \uC870\uC815"), /*#__PURE__*/React.createElement(Button, {
    size: "sm",
    variant: "secondary",
    onClick: () => go('stock')
  }, "\uBC1C\uC8FC \uAC80\uD1A0"))))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: '1.6fr 1fr',
      gap: 22,
      alignItems: 'start'
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padded: true
  }, /*#__PURE__*/React.createElement(SectionHeader, {
    title: "\uC2DC\uAC04\uB300\uBCC4 \uC8FC\uBB38",
    description: "7\uC2DC \u2013 22\uC2DC \xB7 \uC624\uB298",
    action: /*#__PURE__*/React.createElement(Badge, {
      tone: "warning"
    }, "\uD53C\uD06C 13\uC2DC")
  }), /*#__PURE__*/React.createElement(Sparkbars, null)), /*#__PURE__*/React.createElement(Card, {
    padded: true
  }, /*#__PURE__*/React.createElement(SectionHeader, {
    title: "\uC778\uAE30 \uBA54\uB274",
    description: "\uC624\uB298 \uD310\uB9E4 \uC0C1\uC704"
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 14
    }
  }, [['아이스 아메리카노', 82, 100], ['오트 라떼', 41, 100], ['콜드브루', 28, 100], ['크루아상', 19, 100]].map(([n, v]) => /*#__PURE__*/React.createElement(ProgressBar, {
    key: n,
    label: n,
    valueLabel: `${v}잔`,
    value: v,
    max: 90
  }))))), /*#__PURE__*/React.createElement(Card, null, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '16px 20px 0'
    }
  }, /*#__PURE__*/React.createElement(SectionHeader, {
    title: "\uCD5C\uADFC \uC8FC\uBB38",
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "ghost",
      iconRight: "chevron-right",
      onClick: () => go('pos')
    }, "\uC8FC\uBB38\uBCF4\uB4DC")
  })), /*#__PURE__*/React.createElement(DataTable, {
    columns: [{
      key: 'no',
      header: '주문번호'
    }, {
      key: 'slot',
      header: '픽업 슬롯'
    }, {
      key: 'items',
      header: '메뉴'
    }, {
      key: 'pay',
      header: '결제수단'
    }, {
      key: 'status',
      header: '상태',
      render: r => /*#__PURE__*/React.createElement(OrderStatus, {
        status: r.status
      })
    }, {
      key: 'amount',
      header: '금액',
      align: 'right'
    }],
    rows: [{
      no: 'A-142',
      slot: '12:20',
      items: '아이스 아메리카노 외 1잔',
      pay: '지갑',
      status: 'making',
      amount: 12800
    }, {
      no: 'A-141',
      slot: '12:20',
      items: '콜드브루',
      pay: '신한카드',
      status: 'ready',
      amount: 5500
    }, {
      no: 'A-139',
      slot: '12:10',
      items: '오트 라떼 외 2잔',
      pay: '지갑',
      status: 'picked',
      amount: 17400
    }, {
      no: 'A-137',
      slot: '12:00',
      items: '카페 라떼',
      pay: '카카오페이',
      status: 'refund',
      amount: -5300
    }]
  })));
}
Object.assign(window, {
  DashboardScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/merchant_console/DashboardScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/merchant_console/PosScreen.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const {
  OrderTicket,
  Button,
  Card,
  Badge,
  Tabs,
  Alert,
  Icon,
  MenuItem,
  QuantityStepper,
  EmptyState
} = window.BeanFlowDesignSystem_c0ae52;
const SEED = {
  placed: [{
    number: 'A-145',
    dueAt: '12:30',
    customer: '이서연',
    lines: [{
      qty: 1,
      name: '콜드브루',
      options: 'ICE'
    }]
  }, {
    number: 'A-144',
    dueAt: '12:30',
    customer: '박도윤',
    lines: [{
      qty: 2,
      name: '카페 라떼',
      options: 'HOT · 저지방'
    }, {
      qty: 1,
      name: '크루아상'
    }]
  }],
  making: [{
    number: 'A-142',
    dueAt: '12:20',
    customer: '김민준',
    lines: [{
      qty: 2,
      name: '아이스 아메리카노',
      options: 'ICE · 샷추가'
    }, {
      qty: 1,
      name: '오트 라떼',
      options: 'HOT'
    }]
  }, {
    number: 'A-143',
    dueAt: '12:20',
    customer: '최지우',
    lines: [{
      qty: 1,
      name: '자몽 허니 블랙티',
      options: 'ICE · 얼음 적게'
    }]
  }],
  ready: [{
    number: 'A-141',
    dueAt: '12:10',
    customer: '정하준',
    lines: [{
      qty: 1,
      name: '콜드브루',
      options: 'ICE'
    }]
  }, {
    number: 'A-140',
    dueAt: '12:10',
    customer: '한소율',
    lines: [{
      qty: 2,
      name: '바스크 치즈케이크'
    }]
  }]
};
const COL = [{
  key: 'placed',
  label: '접수',
  next: 'making',
  cta: '제조 시작'
}, {
  key: 'making',
  label: '제조 중',
  next: 'ready',
  cta: '준비 완료'
}, {
  key: 'ready',
  label: '픽업 대기',
  next: null,
  cta: '픽업 완료'
}];
function PosScreen({
  notify
}) {
  const [board, setBoard] = React.useState(SEED);
  const move = (from, t) => {
    const col = COL.find(c => c.key === from);
    setBoard(b => {
      const next = {
        ...b,
        [from]: b[from].filter(x => x.number !== t.number)
      };
      if (col.next) next[col.next] = [...b[col.next], t];
      return next;
    });
    notify(col.next === 'ready' ? {
      title: `${t.number} 준비 완료 알림 발송`,
      icon: 'bell-ring',
      tone: 'accent',
      body: '고객에게 픽업 알림이 전송됐어요.'
    } : {
      title: `${t.number} ${col.cta}`,
      icon: 'check'
    });
  };
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 18
    }
  }, /*#__PURE__*/React.createElement(Alert, {
    tone: "warning",
    title: "\uC810\uC2EC \uD53C\uD06C \uC9C4\uD589 \uC911",
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "secondary"
    }, "\uC2AC\uB86F \uC815\uC6D0 \uC870\uC815")
  }, "12:20 \uC2AC\uB86F\uC774 \uB9C8\uAC10\uB410\uC5B4\uC694. \uB2E4\uC74C \uC5EC\uC720 \uC2AC\uB86F\uC740 12:40\uC785\uB2C8\uB2E4."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 12
    }
  }, /*#__PURE__*/React.createElement(Tabs, {
    variant: "pill",
    value: "today",
    items: [{
      value: 'today',
      label: '오늘'
    }, {
      value: 'slot',
      label: '슬롯별'
    }]
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      marginLeft: 'auto',
      display: 'flex',
      gap: 8,
      alignItems: 'center'
    }
  }, /*#__PURE__*/React.createElement(Badge, {
    tone: "info",
    dot: true
  }, "\uC811\uC218 ", board.placed.length), /*#__PURE__*/React.createElement(Badge, {
    tone: "warning",
    dot: true
  }, "\uC81C\uC870 ", board.making.length), /*#__PURE__*/React.createElement(Badge, {
    tone: "success",
    dot: true
  }, "\uB300\uAE30 ", board.ready.length))), /*#__PURE__*/React.createElement("div", {
    className: "bf-cols"
  }, COL.map(c => /*#__PURE__*/React.createElement("div", {
    key: c.key,
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 12
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      padding: '0 2px'
    }
  }, /*#__PURE__*/React.createElement("b", {
    style: {
      color: 'var(--text-strong)',
      fontSize: 'var(--fs-title-3)'
    }
  }, c.label), /*#__PURE__*/React.createElement("span", {
    className: "bf-num",
    style: {
      color: 'var(--text-faint)',
      fontWeight: 600
    }
  }, board[c.key].length)), board[c.key].length === 0 ? /*#__PURE__*/React.createElement(Card, {
    variant: "flat"
  }, /*#__PURE__*/React.createElement(EmptyState, {
    icon: "coffee",
    title: "\uBE44\uC5B4 \uC788\uC5B4\uC694"
  })) : board[c.key].map(t => /*#__PURE__*/React.createElement(OrderTicket, _extends({
    key: t.number
  }, t, {
    status: c.key,
    actions: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "secondary",
      iconLeft: "printer"
    }, "\uC601\uC218\uC99D"), /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      block: true,
      onClick: () => move(c.key, t)
    }, c.cta))
  })))))));
}
Object.assign(window, {
  PosScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/merchant_console/PosScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/merchant_console/SettlementScreen.jsx
try { (() => {
const {
  Card,
  DataTable,
  Badge,
  Button,
  StatTile,
  Tabs,
  Dialog,
  Input,
  Select,
  Alert,
  SectionHeader,
  OrderStatus,
  Icon,
  ListRow
} = window.BeanFlowDesignSystem_c0ae52;
const SETTLE = [{
  date: '07-21',
  orders: 184,
  gross: 1284000,
  fee: -38520,
  adjust: 0,
  net: 1245480,
  status: 'done'
}, {
  date: '07-22',
  orders: 171,
  gross: 1142500,
  fee: -34275,
  adjust: -12400,
  net: 1095825,
  status: 'done'
}, {
  date: '07-23',
  orders: 166,
  gross: 1098000,
  fee: -32940,
  adjust: -5800,
  net: 1059260,
  status: 'review'
}, {
  date: '07-24',
  orders: 192,
  gross: 1331200,
  fee: -39936,
  adjust: 0,
  net: 1291264,
  status: 'pending'
}, {
  date: '07-25',
  orders: 188,
  gross: 1276400,
  fee: -38292,
  adjust: -3200,
  net: 1234908,
  status: 'pending'
}];
const S = {
  done: ['success', '지급 완료'],
  review: ['warning', '이의제기 심사'],
  pending: ['info', '지급 예정']
};
const DISPUTES = [{
  id: 'D-2207',
  date: '07-23',
  reason: '부분 환불 이중 차감',
  amount: -5800,
  status: 'making',
  label: '심사 중'
}, {
  id: 'D-2198',
  date: '07-22',
  reason: '쿠폰 중복 적용 정산 누락',
  amount: -12400,
  status: 'ready',
  label: '보완 요청'
}];
function SettlementScreen({
  notify
}) {
  const [tab, setTab] = React.useState('settle');
  const [open, setOpen] = React.useState(false);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 18
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "bf-grid4"
  }, /*#__PURE__*/React.createElement(StatTile, {
    label: "\uC774\uBC88 \uC8FC \uC815\uC0B0 \uC608\uC815",
    value: 2526172,
    unit: "\uC6D0",
    caption: "07-24 ~ 07-25"
  }), /*#__PURE__*/React.createElement(StatTile, {
    label: "\uC218\uC218\uB8CC",
    value: -78228,
    unit: "\uC6D0",
    caption: "3.0% \xB7 VAT \uBCC4\uB3C4"
  }), /*#__PURE__*/React.createElement(StatTile, {
    label: "\uC870\uC815 \uAE08\uC561",
    value: -21400,
    unit: "\uC6D0",
    delta: "3\uAC74",
    trend: "down",
    caption: "\uD658\uBD88\xB7\uCFE0\uD3F0 \uC815\uC0B0"
  }), /*#__PURE__*/React.createElement(StatTile, {
    label: "\uC774\uC758\uC81C\uAE30",
    value: 2,
    unit: "\uAC74",
    delta: "\uC2EC\uC0AC \uC911",
    trend: "flat",
    caption: "\uD3C9\uADE0 \uCC98\uB9AC 1.4\uC77C"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 12
    }
  }, /*#__PURE__*/React.createElement(Tabs, {
    value: tab,
    onChange: setTab,
    items: [{
      value: 'settle',
      label: '정산 내역'
    }, {
      value: 'dispute',
      label: '이의제기',
      count: 2
    }]
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      marginLeft: 'auto',
      display: 'flex',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: 150
    }
  }, /*#__PURE__*/React.createElement(Select, {
    size: "sm",
    options: [{
      value: '7',
      label: '최근 7일'
    }, {
      value: '30',
      label: '최근 30일'
    }]
  })), /*#__PURE__*/React.createElement(Button, {
    size: "sm",
    variant: "secondary",
    iconLeft: "gavel",
    onClick: () => setOpen(true)
  }, "\uC774\uC758\uC81C\uAE30"))), tab === 'settle' ? /*#__PURE__*/React.createElement(Card, null, /*#__PURE__*/React.createElement(DataTable, {
    columns: [{
      key: 'date',
      header: '정산일'
    }, {
      key: 'orders',
      header: '건수',
      align: 'right'
    }, {
      key: 'gross',
      header: '매출액',
      align: 'right'
    }, {
      key: 'fee',
      header: '수수료',
      align: 'right'
    }, {
      key: 'adjust',
      header: '조정',
      align: 'right'
    }, {
      key: 'net',
      header: '지급액',
      align: 'right'
    }, {
      key: 'status',
      header: '상태',
      render: r => /*#__PURE__*/React.createElement(Badge, {
        tone: S[r.status][0],
        dot: true
      }, S[r.status][1])
    }, {
      key: 'act',
      header: '',
      render: r => /*#__PURE__*/React.createElement(Button, {
        size: "sm",
        variant: "ghost",
        iconRight: "chevron-right"
      }, "\uC0C1\uC138")
    }],
    rows: SETTLE
  })) : /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: '1.4fr 1fr',
      gap: 18,
      alignItems: 'start'
    }
  }, /*#__PURE__*/React.createElement(Card, null, DISPUTES.map(d => /*#__PURE__*/React.createElement(ListRow, {
    key: d.id,
    leading: /*#__PURE__*/React.createElement(Icon, {
      name: "gavel"
    }),
    title: `${d.id} · ${d.reason}`,
    subtitle: `정산일 ${d.date} · 요청 금액 ${Math.abs(d.amount).toLocaleString('ko-KR')}원`,
    trailing: /*#__PURE__*/React.createElement(OrderStatus, {
      status: d.status,
      label: d.label
    }),
    chevron: true,
    onClick: () => {}
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: 16
    }
  }, /*#__PURE__*/React.createElement(Alert, {
    tone: "info",
    title: "\uC774\uC758\uC81C\uAE30 \uCC98\uB9AC \uAE30\uC900"
  }, "\uC815\uC0B0 \uD655\uC815\uC77C\uB85C\uBD80\uD130 14\uC77C \uC774\uB0B4 \uC811\uC218\uBD84\uC5D0 \uD55C\uD574 \uC7AC\uC815\uC0B0\uB429\uB2C8\uB2E4. \uC2EC\uC0AC \uACB0\uACFC\uB294 \uC54C\uB9BC\uACFC \uC774\uBA54\uC77C\uB85C \uC548\uB0B4\uB3FC\uC694."))), /*#__PURE__*/React.createElement(Card, {
    padded: true
  }, /*#__PURE__*/React.createElement(SectionHeader, {
    title: "\uC870\uC815 \uC0AC\uC720 \uBD84\uD3EC",
    description: "\uCD5C\uADFC 30\uC77C"
  }), [['부분 환불', 42], ['쿠폰 정산 차이', 31], ['중복 결제 취소', 18], ['기타', 9]].map(([n, v]) => /*#__PURE__*/React.createElement("div", {
    key: n,
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 12,
      padding: '8px 0'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 110,
      fontSize: 13,
      color: 'var(--text-body)'
    }
  }, n), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      height: 8,
      borderRadius: 999,
      background: 'var(--crema-200)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: v + '%',
      height: '100%',
      borderRadius: 999,
      background: 'var(--espresso-500)'
    }
  })), /*#__PURE__*/React.createElement("span", {
    className: "bf-num",
    style: {
      width: 34,
      textAlign: 'right',
      fontSize: 13,
      color: 'var(--text-muted)'
    }
  }, v, "%"))))), /*#__PURE__*/React.createElement(Dialog, {
    open: open,
    title: "\uC815\uC0B0 \uC774\uC758\uC81C\uAE30",
    description: "\uC870\uC815 \uB0B4\uC5ED\uC5D0 \uC774\uACAC\uC774 \uC788\uB294 \uC815\uC0B0 \uAC74\uC744 \uC811\uC218\uD569\uB2C8\uB2E4. \uC99D\uBE59\uC744 \uD568\uAED8 \uC62C\uB9AC\uBA74 \uCC98\uB9AC\uAC00 \uBE68\uB77C\uC838\uC694.",
    onClose: () => setOpen(false),
    actions: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(Button, {
      variant: "secondary",
      block: true,
      onClick: () => setOpen(false)
    }, "\uCDE8\uC18C"), /*#__PURE__*/React.createElement(Button, {
      block: true,
      onClick: () => {
        setOpen(false);
        notify({
          title: '이의제기가 접수됐어요',
          icon: 'gavel',
          body: '평균 1.4일 내 결과를 알려드려요.'
        });
      }
    }, "\uC811\uC218\uD558\uAE30"))
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 14,
      marginTop: 18
    }
  }, /*#__PURE__*/React.createElement(Select, {
    label: "\uC815\uC0B0\uC77C",
    options: [{
      value: '0723',
      label: '07-23 · 1,059,260원'
    }, {
      value: '0722',
      label: '07-22 · 1,095,825원'
    }]
  }), /*#__PURE__*/React.createElement(Select, {
    label: "\uC0AC\uC720",
    options: [{
      value: 'refund',
      label: '부분 환불 이중 차감'
    }, {
      value: 'coupon',
      label: '쿠폰 정산 차이'
    }, {
      value: 'dup',
      label: '중복 결제 취소 누락'
    }]
  }), /*#__PURE__*/React.createElement(Input, {
    label: "\uC694\uCCAD \uAE08\uC561",
    defaultValue: "5,800",
    suffix: "\uC6D0",
    required: true
  }), /*#__PURE__*/React.createElement(Input, {
    label: "\uC0C1\uC138 \uB0B4\uC6A9",
    placeholder: "\uC5B4\uB5A4 \uC8FC\uBB38\uC5D0\uC11C \uCC28\uC774\uAC00 \uBC1C\uC0DD\uD588\uB294\uC9C0 \uC54C\uB824\uC8FC\uC138\uC694"
  }))));
}
Object.assign(window, {
  SettlementScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/merchant_console/SettlementScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/merchant_console/StockScreen.jsx
try { (() => {
const {
  Card,
  DataTable,
  Badge,
  Button,
  Switch,
  SearchField,
  Select,
  ProgressBar,
  Alert,
  QuantityStepper,
  SectionHeader,
  Icon
} = window.BeanFlowDesignSystem_c0ae52;
const ITEMS = [{
  name: '원두 (하우스 블렌드)',
  unit: 'kg',
  stock: 12.4,
  par: 20,
  sold: 4.2,
  status: 'ok'
}, {
  name: '오트밀크',
  unit: '팩',
  stock: 6,
  par: 24,
  sold: 11,
  status: 'low'
}, {
  name: '우유 (1L)',
  unit: '팩',
  stock: 18,
  par: 30,
  sold: 9,
  status: 'ok'
}, {
  name: '아이스컵 (16oz)',
  unit: '개',
  stock: 240,
  par: 500,
  sold: 186,
  status: 'low'
}, {
  name: '바스크 치즈케이크',
  unit: '조각',
  stock: 2,
  par: 12,
  sold: 10,
  status: 'critical'
}, {
  name: '크루아상',
  unit: '개',
  stock: 0,
  par: 20,
  sold: 20,
  status: 'out'
}];
const TONE = {
  ok: ['success', '충분'],
  low: ['warning', '부족'],
  critical: ['danger', '임박'],
  out: ['neutral', '품절']
};
function StockScreen({
  notify
}) {
  const [q, setQ] = React.useState('');
  const rows = ITEMS.filter(i => i.name.includes(q));
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 18
    }
  }, /*#__PURE__*/React.createElement(Alert, {
    tone: "danger",
    title: "\uD488\uC808 \uD56D\uBAA9 1\uAC74 \xB7 \uC784\uBC15 1\uAC74",
    action: /*#__PURE__*/React.createElement(Button, {
      size: "sm",
      variant: "secondary",
      onClick: () => notify({
        title: '발주서를 생성했어요',
        icon: 'package'
      })
    }, "\uBC1C\uC8FC\uC11C \uB9CC\uB4E4\uAE30")
  }, "\uD06C\uB8E8\uC544\uC0C1\uC774 \uD488\uC808 \uCC98\uB9AC\uB418\uC5B4 \uACE0\uAC1D \uBA54\uB274\uC5D0\uC11C \uC790\uB3D9\uC73C\uB85C \uBE44\uD65C\uC131\uD654\uB410\uC5B4\uC694."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 12,
      alignItems: 'center'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: 320
    }
  }, /*#__PURE__*/React.createElement(SearchField, {
    value: q,
    onChange: e => setQ(e.target.value),
    onClear: () => setQ(''),
    placeholder: "\uD488\uBAA9 \uAC80\uC0C9",
    size: "sm"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      width: 170
    }
  }, /*#__PURE__*/React.createElement(Select, {
    size: "sm",
    options: [{
      value: 'all',
      label: '전체 카테고리'
    }, {
      value: 'bean',
      label: '원두'
    }, {
      value: 'dairy',
      label: '유제품'
    }, {
      value: 'pkg',
      label: '부자재'
    }]
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginLeft: 'auto',
      display: 'flex',
      gap: 10,
      alignItems: 'center'
    }
  }, /*#__PURE__*/React.createElement(Switch, {
    label: "\uC790\uB3D9 \uD488\uC808 \uC5F0\uB3D9",
    defaultChecked: true
  }), /*#__PURE__*/React.createElement(Button, {
    size: "sm",
    iconLeft: "plus"
  }, "\uD488\uBAA9 \uCD94\uAC00"))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: '1.9fr 1fr',
      gap: 18,
      alignItems: 'start'
    }
  }, /*#__PURE__*/React.createElement(Card, null, /*#__PURE__*/React.createElement(DataTable, {
    columns: [{
      key: 'name',
      header: '품목'
    }, {
      key: 'level',
      header: '재고 수준',
      render: r => /*#__PURE__*/React.createElement("div", {
        style: {
          width: 150
        }
      }, /*#__PURE__*/React.createElement(ProgressBar, {
        value: r.stock,
        max: r.par,
        tone: r.status === 'ok' ? 'brand' : 'accent'
      }))
    }, {
      key: 'stock',
      header: '현재고',
      align: 'right',
      render: r => `${r.stock.toLocaleString('ko-KR')}${r.unit}`
    }, {
      key: 'sold',
      header: '오늘 소진',
      align: 'right',
      render: r => `${r.sold}${r.unit}`
    }, {
      key: 'status',
      header: '상태',
      render: r => /*#__PURE__*/React.createElement(Badge, {
        tone: TONE[r.status][0],
        dot: true
      }, TONE[r.status][1])
    }, {
      key: 'adj',
      header: '조정',
      render: r => /*#__PURE__*/React.createElement(QuantityStepper, {
        value: Math.max(1, Math.round(r.stock)),
        onChange: () => {}
      })
    }],
    rows: rows,
    empty: "\uAC80\uC0C9 \uACB0\uACFC\uAC00 \uC5C6\uC5B4\uC694"
  })), /*#__PURE__*/React.createElement(Card, {
    padded: true
  }, /*#__PURE__*/React.createElement(SectionHeader, {
    title: "\uC624\uB298\uC758 \uBC1C\uC8FC \uC81C\uC548",
    eyebrow: "AI \uC778\uC0AC\uC774\uD2B8",
    description: "\uCD5C\uADFC 4\uC8FC \uC18C\uC9C4 \uC18D\uB3C4 \uAE30\uC900"
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 12
    }
  }, [['오트밀크', '+2박스', '소진 속도 +22%'], ['아이스컵 16oz', '+1박스', '피크 3일 연속 부족'], ['크루아상', '+20개', '오전 완판']].map(([n, qy, why]) => /*#__PURE__*/React.createElement("div", {
    key: n,
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      padding: '10px 12px',
      border: '1px solid var(--border-hair)',
      borderRadius: 'var(--r-md)'
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "package",
    size: 18,
    style: {
      color: 'var(--caramel-600)'
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontWeight: 600,
      color: 'var(--text-strong)',
      fontSize: 14
    }
  }, n, " ", /*#__PURE__*/React.createElement("span", {
    style: {
      color: 'var(--caramel-600)'
    }
  }, qy)), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12,
      color: 'var(--text-muted)'
    }
  }, why))))), /*#__PURE__*/React.createElement(Button, {
    block: true,
    style: {
      marginTop: 16
    },
    onClick: () => notify({
      title: '발주 제안을 담았어요',
      icon: 'check'
    })
  }, "\uC81C\uC548 \uC804\uCCB4 \uB2F4\uAE30"))));
}
Object.assign(window, {
  StockScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/merchant_console/StockScreen.jsx", error: String((e && e.message) || e) }); }

__ds_ns.BalanceCard = __ds_scope.BalanceCard;

__ds_ns.CouponCard = __ds_scope.CouponCard;

__ds_ns.DataTable = __ds_scope.DataTable;

__ds_ns.MenuItem = __ds_scope.MenuItem;

__ds_ns.OrderStatus = __ds_scope.OrderStatus;

__ds_ns.OrderTicket = __ds_scope.OrderTicket;

__ds_ns.PickupSlots = __ds_scope.PickupSlots;

__ds_ns.StatTile = __ds_scope.StatTile;

__ds_ns.StoreCard = __ds_scope.StoreCard;

__ds_ns.Badge = __ds_scope.Badge;

__ds_ns.Button = __ds_scope.Button;

__ds_ns.Card = __ds_scope.Card;

__ds_ns.Icon = __ds_scope.Icon;

__ds_ns.IconButton = __ds_scope.IconButton;

__ds_ns.SectionHeader = __ds_scope.SectionHeader;

__ds_ns.Alert = __ds_scope.Alert;

__ds_ns.Dialog = __ds_scope.Dialog;

__ds_ns.EmptyState = __ds_scope.EmptyState;

__ds_ns.ProgressBar = __ds_scope.ProgressBar;

__ds_ns.Toast = __ds_scope.Toast;

__ds_ns.Checkbox = __ds_scope.Checkbox;

__ds_ns.Input = __ds_scope.Input;

__ds_ns.QuantityStepper = __ds_scope.QuantityStepper;

__ds_ns.Radio = __ds_scope.Radio;

__ds_ns.SearchField = __ds_scope.SearchField;

__ds_ns.Select = __ds_scope.Select;

__ds_ns.Switch = __ds_scope.Switch;

__ds_ns.ListRow = __ds_scope.ListRow;

__ds_ns.SideNav = __ds_scope.SideNav;

__ds_ns.TabBar = __ds_scope.TabBar;

__ds_ns.Tabs = __ds_scope.Tabs;

__ds_ns.TopBar = __ds_scope.TopBar;

})();
