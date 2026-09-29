import 'package:flutter/material.dart';

/// The fixed icons a CUSTOM floor object may wear. Keys match the store's
/// FLOOR_OBJECT_ICONS (FloorObjects.kt): the AI picks one of these keys, it
/// never draws an image. An unknown key (a newer store) shows the star.
const Map<String, IconData> floorObjectIcons = {
  'music': Icons.music_note,
  'speaker': Icons.speaker,
  'tv': Icons.tv,
  'piano': Icons.piano,
  'plant': Icons.local_florist,
  'coat': Icons.checkroom,
  'stairs': Icons.stairs,
  'elevator': Icons.elevator,
  'fireplace': Icons.fireplace,
  'games': Icons.sports_esports,
  'casino': Icons.casino,
  'atm': Icons.local_atm,
  'window': Icons.window,
  'door': Icons.door_front_door,
  'wine': Icons.wine_bar,
  'coffee': Icons.coffee,
  'cake': Icons.cake,
  'fridge': Icons.kitchen,
  'storage': Icons.inventory_2,
  'star': Icons.star,
};

/// The look of the built-in room types (null = no icon, e.g. pool / pillar).
IconData? floorObjectTypeIcon(String type, String? icon) => switch (type) {
  'ENTRANCE' => Icons.door_front_door,
  'HOST_STAND' => Icons.person_pin,
  'KITCHEN' => Icons.soup_kitchen,
  'RESTROOMS' => Icons.wc,
  'STAGE' => Icons.theater_comedy,
  'CUSTOM' => floorObjectIcons[icon] ?? Icons.star,
  _ => null,
};
