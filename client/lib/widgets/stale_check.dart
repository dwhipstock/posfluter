import '../api.dart';

/// A check that is no longer an open bill: paid, cancelled or merged on
/// another device, or (an unpaid counter / kiosk order) expired by the store.
bool isCheckGone(String status) => status != 'OPEN' && status != 'TOTAL_LOCKED';

/// A refusal that may mean the check went away under the screen (expired,
/// paid or cleared elsewhere): worth one fresh look before showing an error.
bool mayBeStaleCheck(Object error) =>
    error is ApiException &&
    (error.status == 404 || _staleCodes.contains(error.code));

const _staleCodes = {
  'check_not_open',
  'order_not_found',
  'order_paid',
  'already_paid',
  'bill_locked',
  'conflict', // an older store's tender refusal on a closed check
};

/// The status of check [id] when it is gone ('MISSING' when the store has no
/// such check); null when it is still open, or the store can't be reached
/// (never guess it away offline).
Future<String?> goneStatus(int id) async {
  try {
    final c = await Api.getCheck(id);
    return isCheckGone(c.status) ? c.status : null;
  } on ApiException catch (e) {
    return e.status == 404 ? 'MISSING' : null;
  } catch (_) {
    return null;
  }
}
