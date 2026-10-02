/// The words of menu specials ([specials.dart]) on every surface — POS,
/// Express counter, kiosk and the menu editor: French, English, Spanish,
/// German and Afrikaans. [lang] is the reader's language code.
class SpecialsText {
  final String lang;
  const SpecialsText(this.lang);

  String _t(String fr, String en, String es, String de, String af) =>
      switch (lang) {
        'fr' => fr,
        'es' => es,
        'de' => de,
        'af' => af,
        _ => en,
      };

  // --- day names (store order: Monday first) ---

  String dayShort(String code) => switch (code) {
    'mon' => _t('lun', 'Mon', 'lun', 'Mo', 'Ma'),
    'tue' => _t('mar', 'Tue', 'mar', 'Di', 'Di'),
    'wed' => _t('mer', 'Wed', 'mié', 'Mi', 'Wo'),
    'thu' => _t('jeu', 'Thu', 'jue', 'Do', 'Do'),
    'fri' => _t('ven', 'Fri', 'vie', 'Fr', 'Vr'),
    'sat' => _t('sam', 'Sat', 'sáb', 'Sa', 'Sa'),
    _ => _t('dim', 'Sun', 'dom', 'So', 'So'),
  };

  String dayLong(String code) => switch (code) {
    'mon' => _t('lundi', 'Monday', 'lunes', 'Montag', 'Maandag'),
    'tue' => _t('mardi', 'Tuesday', 'martes', 'Dienstag', 'Dinsdag'),
    'wed' => _t('mercredi', 'Wednesday', 'miércoles', 'Mittwoch', 'Woensdag'),
    'thu' => _t('jeudi', 'Thursday', 'jueves', 'Donnerstag', 'Donderdag'),
    'fri' => _t('vendredi', 'Friday', 'viernes', 'Freitag', 'Vrydag'),
    'sat' => _t('samedi', 'Saturday', 'sábado', 'Samstag', 'Saterdag'),
    _ => _t('dimanche', 'Sunday', 'domingo', 'Sonntag', 'Sondag'),
  };

  String get _and => _t(' et ', ' & ', ' y ', ' & ', ' en ');

  /// "Fri & Sat", "Mon, Wed & Fri" (short names).
  String daysShort(List<String> days) {
    final n = [for (final d in days) dayShort(d)];
    if (n.isEmpty) return '';
    if (n.length == 1) return n.first;
    return n.sublist(0, n.length - 1).join(', ') + _and + n.last;
  }

  /// "Fri & Sat only": an item sold only on some days.
  String onlyOn(List<String> days) => _t(
    '${daysShort(days)} seulement',
    '${daysShort(days)} only',
    'solo ${daysShort(days)}',
    'nur ${daysShort(days)}',
    'slegs ${daysShort(days)}',
  );

  // --- a special's name (the store's rule: sdk/MenuSpecials.label) ---

  String get happyHour =>
      _t('Happy hour', 'Happy hour', 'Hora feliz', 'Happy Hour', 'Happy hour');

  String daySpecial(String code) => _t(
    'Spécial du ${dayLong(code)}',
    '${dayLong(code)} special',
    'Especial del ${dayLong(code)}',
    '${dayLong(code)}sangebot',
    '${dayLong(code)}-spesiaal',
  );

  String get special =>
      _t('Spécial', 'Special', 'Especial', 'Angebot', 'Spesiaal');

  /// The menu's row of what is on special right now.
  String get todaysSpecials => _t(
    'Spéciaux du jour',
    'Today’s specials',
    'Especiales de hoy',
    'Heutige Angebote',
    'Vandag se spesiale',
  );

  /// Tapping an item that isn't sold today.
  String notToday(List<String> days) => _t(
    'Pas en vente aujourd’hui — ${daysShort(days)} seulement',
    'Not sold today — ${daysShort(days)} only',
    'No se vende hoy — solo ${daysShort(days)}',
    'Heute nicht erhältlich — nur ${daysShort(days)}',
    'Nie vandag te koop nie — slegs ${daysShort(days)}',
  );

  /// The menu price a special replaces: "reg. $12.45".
  String regular(String money) => _t(
    'rég. $money',
    'reg. $money',
    'normal $money',
    'regulär $money',
    'gewoonlik $money',
  );

  // --- the menu editor ---

  String get specials =>
      _t('Spéciaux', 'Specials', 'Especiales', 'Angebote', 'Spesiales');

  String get specialsHint => _t(
    'Un prix plus bas certains jours, à certaines heures si vous voulez.',
    'A lower price on some days, at some hours if you like.',
    'Un precio más bajo algunos días, a ciertas horas si quiere.',
    'Ein niedrigerer Preis an manchen Tagen, auf Wunsch zu bestimmten Zeiten.',
    '’n Laer prys op sommige dae, op sekere ure as u wil.',
  );

  String get addSpecial => _t(
    'Ajouter un spécial',
    'Add a special',
    'Añadir un especial',
    'Angebot hinzufügen',
    'Voeg ’n spesiaal by',
  );

  String get removeSpecial => _t(
    'Retirer ce spécial',
    'Remove this special',
    'Quitar este especial',
    'Dieses Angebot entfernen',
    'Verwyder hierdie spesiaal',
  );

  String get availableOnlyOn => _t(
    'En vente seulement le',
    'Available only on',
    'Disponible solo los',
    'Nur erhältlich am',
    'Slegs beskikbaar op',
  );

  String get everyDayHint => _t(
    'Aucun jour choisi = tous les jours',
    'No day picked = every day',
    'Ningún día elegido = todos los días',
    'Kein Tag gewählt = jeden Tag',
    'Geen dag gekies nie = elke dag',
  );

  String get from => _t('De', 'From', 'Desde', 'Von', 'Van');
  String get to => _t('À', 'To', 'Hasta', 'Bis', 'Tot');

  String get timeHint => _t(
    'Heures (facultatif, HH:mm)',
    'Hours (optional, HH:mm)',
    'Horas (opcional, HH:mm)',
    'Uhrzeit (optional, HH:mm)',
    'Ure (opsioneel, HH:mm)',
  );

  String get nameOptional => _t(
    'Nom (facultatif)',
    'Name (optional)',
    'Nombre (opcional)',
    'Bezeichnung (optional)',
    'Naam (opsioneel)',
  );

  String get specialPrice => _t(
    'Prix spécial',
    'Special price',
    'Precio especial',
    'Angebotspreis',
    'Spesiale prys',
  );

  String get pickADay => _t(
    'Choisissez au moins un jour pour chaque spécial.',
    'Pick at least one day for each special.',
    'Elija al menos un día para cada especial.',
    'Wählen Sie für jedes Angebot mindestens einen Tag.',
    'Kies ten minste een dag vir elke spesiaal.',
  );

  String get setAPrice => _t(
    'Donnez un prix spécial à au moins une taille.',
    'Give at least one size a special price.',
    'Dé un precio especial a al menos un tamaño.',
    'Geben Sie mindestens einer Größe einen Angebotspreis.',
    'Gee ten minste een grootte ’n spesiale prys.',
  );

  String get bothTimes => _t(
    'Indiquez les deux heures (début et fin), ou aucune.',
    'Enter both times (start and end), or neither.',
    'Indique las dos horas (inicio y fin), o ninguna.',
    'Geben Sie beide Zeiten (Beginn und Ende) an oder keine.',
    'Gee albei tye (begin en einde), of geen.',
  );

  String get badTime => _t(
    'Écrivez l’heure ainsi\u00A0: 16:00.',
    'Write the time like 16:00.',
    'Escriba la hora así: 16:00.',
    'Schreiben Sie die Uhrzeit so: 16:00.',
    'Skryf die tyd so: 16:00.',
  );

  String get sameTimes => _t(
    'Le début et la fin ne peuvent pas être identiques.',
    'The start and end can’t be the same.',
    'El inicio y el fin no pueden ser iguales.',
    'Beginn und Ende dürfen nicht gleich sein.',
    'Die begin en einde kan nie dieselfde wees nie.',
  );
}
