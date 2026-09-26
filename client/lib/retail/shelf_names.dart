/// US Spanish names for the Sage & Poppy shelf's facets: departments and
/// every style / varietal / type the catalog carries. The store's catalog is
/// English only (a product's brand and name stay as printed on the bottle), so
/// the counter translates these labels on screen; filtering still uses the
/// English values.
///
/// Keep in step with the generator's word lists in
/// server/src/main/kotlin/dev/dwhipstock/pos/customers/sagepoppy/SagePoppyCatalog.kt
/// (styles, varietals, spiritTypes, rtdKinds, curatedFacets, otherProducts)
/// and SagePoppySeed.Cat. Names US Spanish speakers use as they are (IPA,
/// Stout, Chardonnay, Bourbon…) map to themselves on purpose.
const Map<String, String> shelfNamesEs = {
  // departments (SagePoppySeed.Cat)
  'Beer & Cider': 'Cerveza y sidra',
  'Wine': 'Vino',
  'Spirits': 'Licores',
  'Seltzers & Coolers': 'Seltzers y bebidas preparadas',
  'Mixers & Soda': 'Mezcladores y refrescos',
  'Snacks': 'Botanas',
  'Ice': 'Hielo',
  'Sundries': 'Artículos varios',

  // beer styles
  'Lager': 'Lager',
  'Light Lager': 'Lager ligera',
  'Mexican-Style Lager': 'Lager estilo mexicano',
  'Pilsner': 'Pilsner',
  'IPA': 'IPA',
  'Hazy IPA': 'IPA turbia',
  'Double IPA': 'IPA doble',
  'West Coast IPA': 'IPA de la Costa Oeste',
  'Pale Ale': 'Pale Ale',
  'Amber Ale': 'Ale ámbar',
  'Brown Ale': 'Ale café',
  'Wheat Beer': 'Cerveza de trigo',
  'Blonde Ale': 'Ale rubia',
  'Stout': 'Stout',
  'Porter': 'Porter',
  'Sour': 'Cerveza ácida',
  'Saison': 'Saison',
  'Cider': 'Sidra',
  'Non-Alcoholic': 'Sin alcohol',

  // wine varietals
  'Cabernet Sauvignon': 'Cabernet Sauvignon',
  'Pinot Noir': 'Pinot Noir',
  'Chardonnay': 'Chardonnay',
  'Sauvignon Blanc': 'Sauvignon Blanc',
  'Zinfandel': 'Zinfandel',
  'Merlot': 'Merlot',
  'Syrah': 'Syrah',
  'Rosé': 'Rosado',
  'Red Blend': 'Mezcla de tintos',
  'White Blend': 'Mezcla de blancos',
  'Pinot Grigio': 'Pinot Grigio',
  'Riesling': 'Riesling',
  'Sparkling': 'Espumoso',
  'Malbec': 'Malbec',
  'Petite Sirah': 'Petite Sirah',
  'Grenache': 'Garnacha',
  'Viognier': 'Viognier',
  'Moscato': 'Moscatel',

  // spirits
  'Vodka': 'Vodka',
  'Gin': 'Ginebra',
  'Bourbon': 'Bourbon',
  'Rye Whiskey': 'Whiskey de centeno',
  'American Whiskey': 'Whiskey americano',
  'Irish Whiskey': 'Whiskey irlandés',
  'Blended Scotch': 'Whisky escocés de mezcla',
  'Single Malt Scotch': 'Whisky escocés de malta',
  'Tequila Blanco': 'Tequila blanco',
  'Tequila Reposado': 'Tequila reposado',
  'Tequila Anejo': 'Tequila añejo',
  'Mezcal': 'Mezcal',
  'White Rum': 'Ron blanco',
  'Spiced Rum': 'Ron especiado',
  'Dark Rum': 'Ron oscuro',
  'Brandy': 'Brandy',
  'Liqueur': 'Licor',
  'Cinnamon Whisky': 'Whisky de canela',

  // seltzers & coolers
  'Hard Seltzer': 'Seltzer con alcohol',
  'Canned Cocktail': 'Cóctel en lata',
  'Hard Tea': 'Té con alcohol',
  'Hard Lemonade': 'Limonada con alcohol',
  'Cooler': 'Bebida preparada',

  // mixers & soda
  'Club Soda': 'Agua mineral',
  'Tonic': 'Agua tónica',
  'Ginger Beer': 'Cerveza de jengibre',
  'Soda': 'Refresco',
  'Sparkling Water': 'Agua con gas',
  'Juice': 'Jugo',
  'Cocktail Mix': 'Mezcla para cócteles',
  'Fresh Garnish': 'Fruta para decorar',

  // snacks
  'Chips': 'Frituras',
  'Nuts': 'Frutos secos',
  'Jerky': 'Carne seca',
  'Candy': 'Dulces',
  'Pretzels': 'Pretzels',
  'Popcorn': 'Palomitas',
  'Crackers': 'Galletas saladas',
  'Snack Meals': 'Comidas instantáneas',
  'Party Supplies': 'Artículos para fiestas',

  // ice
  'Bagged Ice': 'Hielo en bolsa',
  'Block Ice': 'Hielo en bloque',

  // sundries
  'Bags': 'Bolsas',
  'Barware': 'Artículos de bar',
  'Gifts': 'Regalos',
  'Coolers': 'Hieleras',
  'Household': 'Artículos para el hogar',
};

final _sizeWords = RegExp(
  r'^([\d.]+) ?(oz|L) (can|cans|bottles|tallboy|bomber|box)$',
);
final _countWords = RegExp(r'^(\d+)-(pack|count|roll)$');

/// A size or pack label in US Spanish: "12 oz can" → "lata de 12 oz",
/// "6-pack" → "paquete de 6", "50-count" → "50 piezas", "Single" →
/// "Individual". Plain measures ("750 ml", "1.75 L", "7 lb") read the same.
String shelfSizeEs(String size) {
  if (size == 'Single') return 'Individual';
  final m = _sizeWords.firstMatch(size);
  if (m != null) {
    final qty = '${m[1]} ${m[2]}';
    return switch (m[3]) {
      'can' => 'lata de $qty',
      'cans' => 'latas de $qty',
      'bottles' => 'botellas de $qty',
      'tallboy' => 'lata alta de $qty',
      'bomber' => 'botella de $qty',
      _ => 'caja de $qty',
    };
  }
  final c = _countWords.firstMatch(size);
  if (c != null) {
    return switch (c[2]) {
      'pack' => 'paquete de ${c[1]}',
      'count' => '${c[1]} piezas',
      _ => '${c[1]} rollos',
    };
  }
  return size;
}
