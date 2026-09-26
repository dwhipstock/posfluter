import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import 'tokens.dart';

/// Brand skins: one code base, screens that look like different products.
///
/// A skin is two things, chosen per brand:
/// - **design tokens** — type family, corner radii, spacing, elevation and
///   motion (colours live in each brand's own palette: [T] for Copper
///   Lantern, `SpColors` for Sage & Poppy);
/// - **layout variants** — how a shared screen is put together: where the
///   navigation sits, how the sign-in screen is composed, how a receipt's
///   header looks, which icon family draws the glyphs.
///
/// The screens read `BrandSkin.of(context)` and branch on the variants; the
/// logic underneath (scanning, the basket, payment, sign-in) is the same code.
/// Copper Lantern's skin is exactly the values its screens always used, so it
/// renders pixel for pixel as before.
enum ShellLayout {
  /// A brand band across the top (Copper Lantern).
  topBar,

  /// A slim navigation rail down the left, the search/scan bar front and
  /// centre, the basket docked on the right (Sage & Poppy).
  leftRail,

  /// A gas station's counter: a black command bar across the top, the pump
  /// grid above the shelf lanes, the basket docked on the right (Pronghorn).
  forecourt,
}

enum LoginLayout {
  /// Brand panel on one side, staff tiles + PIN pad on the other (Copper).
  brandPanel,

  /// One airy column: greeting, staff as avatar chips, round-key PIN pad (S&P).
  centeredStack,
}

enum ReceiptHeaderStyle {
  /// Centred store lines (Copper).
  centered,

  /// A wordmark band over the paper, left-aligned (S&P).
  wordmark,
}

enum IconStyle {
  /// Lucide line icons (Copper).
  line,

  /// Material rounded, filled glyphs (S&P).
  rounded,
}

@immutable
class BrandSkin extends ThemeExtension<BrandSkin> {
  final String id;

  // type
  final String fontFamily;
  final List<String> fontFallback;

  /// Headline tracking: tight on a modern grotesk, none on Inter.
  final double headlineTracking;

  // shape: radii for small bits (tags), controls, panels; tiles; pills
  final BorderRadius radiusSmall, radiusMedium, radiusLarge, tileRadius;

  /// True: buttons, chips and the search bar are full pills.
  final bool pillControls;

  // spacing: the base unit and the gutter around content
  final double space, gutter;

  // elevation: the one shadow for tappable tiles (empty = flat)
  final List<BoxShadow> raised;

  // motion
  final Duration fast, normal;
  final Curve ease;

  // layout variants
  final ShellLayout shell;
  final LoginLayout login;
  final ReceiptHeaderStyle receiptHeader;
  final IconStyle icons;

  const BrandSkin({
    required this.id,
    required this.fontFamily,
    required this.fontFallback,
    required this.headlineTracking,
    required this.radiusSmall,
    required this.radiusMedium,
    required this.radiusLarge,
    required this.tileRadius,
    required this.pillControls,
    required this.space,
    required this.gutter,
    required this.raised,
    required this.fast,
    required this.normal,
    required this.ease,
    required this.shell,
    required this.login,
    required this.receiptHeader,
    required this.icons,
  });

  /// The Copper Lantern pubs: the tokens their screens were built with.
  static final copper = BrandSkin(
    id: 'copper-lantern',
    fontFamily: T.fontFamily,
    fontFallback: const ['NotoSans'],
    headlineTracking: 0,
    radiusSmall: T.radiusSmall,
    radiusMedium: T.radiusMedium,
    radiusLarge: T.radiusLarge,
    tileRadius: T.radiusLarge,
    pillControls: false,
    space: 8,
    gutter: 16,
    raised: T.raised,
    fast: T.dFast,
    normal: T.dNormal,
    ease: T.ease,
    shell: ShellLayout.topBar,
    login: LoginLayout.brandPanel,
    receiptHeader: ReceiptHeaderStyle.centered,
    icons: IconStyle.line,
  );

  /// Sage & Poppy: modern California retail — a geometric grotesk, crisp
  /// square tiles and pill controls, flat surfaces, a quicker, springier feel.
  static final sagePoppy = BrandSkin(
    id: 'sage-poppy',
    fontFamily: 'PlusJakartaSans',
    fontFallback: const ['NotoSans'],
    headlineTracking: -0.4,
    radiusSmall: BorderRadius.circular(3),
    radiusMedium: BorderRadius.circular(6),
    radiusLarge: BorderRadius.circular(18),
    tileRadius: BorderRadius.circular(4),
    pillControls: true,
    space: 6,
    gutter: 20,
    raised: const [],
    fast: const Duration(milliseconds: 90),
    normal: const Duration(milliseconds: 140),
    ease: Curves.easeOutCubic,
    shell: ShellLayout.leftRail,
    login: LoginLayout.centeredStack,
    receiptHeader: ReceiptHeaderStyle.wordmark,
    icons: IconStyle.rounded,
  );

  /// Pronghorn Fuel & Market: a highway c-store counter — Barlow, bold and
  /// high-contrast on near-black, squared-off tiles with a small radius,
  /// no shadows, quick motion; built to be read at a glance from a step away.
  static final pronghorn = BrandSkin(
    id: 'pronghorn',
    fontFamily: 'Barlow',
    fontFallback: const ['NotoSans'],
    headlineTracking: 0.2,
    radiusSmall: BorderRadius.circular(3),
    radiusMedium: BorderRadius.circular(6),
    radiusLarge: BorderRadius.circular(10),
    tileRadius: BorderRadius.circular(8),
    pillControls: false,
    space: 6,
    gutter: 16,
    raised: const [],
    fast: const Duration(milliseconds: 80),
    normal: const Duration(milliseconds: 120),
    ease: Curves.easeOutCubic,
    shell: ShellLayout.forecourt,
    login: LoginLayout.centeredStack,
    receiptHeader: ReceiptHeaderStyle.wordmark,
    icons: IconStyle.rounded,
  );

  static BrandSkin of(BuildContext context) =>
      Theme.of(context).extension<BrandSkin>() ?? copper;

  /// Text in this skin's family.
  TextStyle text({
    double size = T.bodySize,
    FontWeight weight = FontWeight.w400,
    Color? color,
    double? height,
  }) => TextStyle(
    fontFamily: fontFamily,
    fontFamilyFallback: fontFallback,
    fontSize: size,
    fontWeight: weight,
    color: color,
    height: height,
    letterSpacing: size >= 20 ? headlineTracking : null,
  );

  /// Numbers that line up in columns.
  TextStyle figures({
    double size = T.priceSize,
    FontWeight weight = FontWeight.w600,
    Color? color,
  }) => text(
    size: size,
    weight: weight,
    color: color,
  ).copyWith(fontFeatures: const [FontFeature.tabularFigures()]);

  /// A control's shape in this skin: a pill, or the medium rounded rectangle.
  OutlinedBorder controlShape() => pillControls
      ? const StadiumBorder()
      : RoundedRectangleBorder(borderRadius: radiusMedium);

  SkinIcons get glyphs =>
      icons == IconStyle.rounded ? SkinIcons.rounded : SkinIcons.line;

  @override
  BrandSkin copyWith() => this;

  @override
  BrandSkin lerp(ThemeExtension<BrandSkin>? other, double t) =>
      other is BrandSkin && t >= .5 ? other : this;
}

/// The glyphs the shared screens use, in each skin's icon family.
class SkinIcons {
  final IconData search, scan, camera, sell, count, receive, register, signOut;
  final IconData user, manager, pin, pinOff, star, grid, list, filter, reset;
  final IconData add, minus, plus, pay, idCard, idOk, idBad, lock, language;
  final IconData backspace, close, check, trending;

  const SkinIcons({
    required this.search,
    required this.scan,
    required this.camera,
    required this.sell,
    required this.count,
    required this.receive,
    required this.register,
    required this.signOut,
    required this.user,
    required this.manager,
    required this.pin,
    required this.pinOff,
    required this.star,
    required this.grid,
    required this.list,
    required this.filter,
    required this.reset,
    required this.add,
    required this.minus,
    required this.plus,
    required this.pay,
    required this.idCard,
    required this.idOk,
    required this.idBad,
    required this.lock,
    required this.language,
    required this.backspace,
    required this.close,
    required this.check,
    required this.trending,
  });

  static const line = SkinIcons(
    search: LucideIcons.search,
    scan: LucideIcons.scanBarcode,
    camera: LucideIcons.camera,
    sell: LucideIcons.shoppingBasket,
    count: LucideIcons.clipboardList,
    receive: LucideIcons.truck,
    register: LucideIcons.landmark,
    signOut: LucideIcons.logOut,
    user: LucideIcons.user,
    manager: LucideIcons.shieldCheck,
    pin: LucideIcons.pin,
    pinOff: LucideIcons.pinOff,
    star: LucideIcons.star,
    grid: LucideIcons.layoutGrid,
    list: LucideIcons.listOrdered,
    filter: LucideIcons.slidersHorizontal,
    reset: LucideIcons.rotateCcw,
    add: LucideIcons.plus,
    minus: LucideIcons.minus,
    plus: LucideIcons.plus,
    pay: LucideIcons.wallet,
    idCard: LucideIcons.idCard,
    idOk: LucideIcons.shieldCheck,
    idBad: LucideIcons.shieldX,
    lock: LucideIcons.lockKeyhole,
    language: LucideIcons.globe,
    backspace: LucideIcons.delete,
    close: LucideIcons.x,
    check: LucideIcons.check,
    trending: LucideIcons.trendingUp,
  );

  static const rounded = SkinIcons(
    search: Icons.search_rounded,
    scan: Icons.qr_code_scanner_rounded,
    camera: Icons.photo_camera_rounded,
    sell: Icons.point_of_sale_rounded,
    count: Icons.inventory_2_rounded,
    receive: Icons.local_shipping_rounded,
    register: Icons.account_balance_wallet_rounded,
    signOut: Icons.logout_rounded,
    user: Icons.person_rounded,
    manager: Icons.verified_user_rounded,
    pin: Icons.push_pin_rounded,
    pinOff: Icons.push_pin_outlined,
    star: Icons.bolt_rounded,
    grid: Icons.grid_view_rounded,
    list: Icons.leaderboard_rounded,
    filter: Icons.tune_rounded,
    reset: Icons.restart_alt_rounded,
    add: Icons.add_rounded,
    minus: Icons.remove_rounded,
    plus: Icons.add_rounded,
    pay: Icons.payments_rounded,
    idCard: Icons.badge_rounded,
    idOk: Icons.verified_rounded,
    idBad: Icons.gpp_bad_rounded,
    lock: Icons.lock_rounded,
    language: Icons.translate_rounded,
    backspace: Icons.backspace_rounded,
    close: Icons.close_rounded,
    check: Icons.check_rounded,
    trending: Icons.trending_up_rounded,
  );
}
