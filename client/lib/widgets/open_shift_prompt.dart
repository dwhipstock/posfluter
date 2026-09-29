import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../api.dart';
import '../design/tokens.dart';
import '../i18n.dart';
import 'pin_pad.dart';

/// The float a new drawer usually starts with ($200).
const defaultOpeningFloatCents = 20000;

/// True when [error] is the store's "no open shift" refusal (a tender, a cash
/// movement… needs a drawer shift to land in).
bool isNoShiftError(Object error) =>
    error is ApiException && error.code == 'no_open_shift';

/// "No cash drawer shift is open. Open one now?" with an editable opening
/// float, then the manager approval (skipped when the user may open shifts)
/// and POST /shifts. Returns true when a shift is now open.
Future<bool> promptOpenShift(BuildContext context) async {
  final floatCents = await showDialog<int>(
    context: context,
    builder: (_) => const _OpenShiftDialog(),
  );
  if (floatCents == null || !context.mounted) return false;
  try {
    final approval = await requireGrant(
      context,
      Perm.openShift,
      title: L.of(context).openShiftApproval,
    );
    if (approval == null) return false;
    await Api.openShift(floatCents, approval.managerPin);
    return true;
  } on ApiException catch (e) {
    // someone else just opened one: that is what we wanted
    if (e.code == 'shift_already_open') return true;
    if (context.mounted) showApiError(context, e);
    return false;
  } catch (e) {
    if (context.mounted) showApiError(context, e);
    return false;
  }
}

/// Runs [op]; when the store answers "no open shift", offers to open one
/// right there and, once it is open, runs [op] again. Other errors rethrow.
Future<void> withOpenShift(
  BuildContext context,
  Future<void> Function() op,
) async {
  try {
    await op();
  } catch (e) {
    if (!isNoShiftError(e) || !context.mounted) rethrow;
    if (await promptOpenShift(context)) await op();
  }
}

String? _offeredForToken;

/// At a manager's sign-in: when no shift is open, offer to open one — once
/// per signed-in session, and "Not now" just carries on.
Future<void> offerShiftAtSignIn(BuildContext context) async {
  final user = Api.currentUser;
  if (user == null || !user.isManager || _offeredForToken == user.token) {
    return;
  }
  _offeredForToken = user.token;
  try {
    if (await Api.currentShift() != null) return;
  } catch (_) {
    return; // advisory only — never block the sign-in
  }
  if (context.mounted) await promptOpenShift(context);
}

class _OpenShiftDialog extends StatefulWidget {
  const _OpenShiftDialog();

  @override
  State<_OpenShiftDialog> createState() => _OpenShiftDialogState();
}

class _OpenShiftDialogState extends State<_OpenShiftDialog> {
  final _float = TextEditingController(
    text: (defaultOpeningFloatCents ~/ 100).toString(),
  );

  @override
  void dispose() {
    _float.dispose();
    super.dispose();
  }

  void _submit() {
    final dollars = int.tryParse(_float.text.trim());
    if (dollars == null || dollars < 0) return;
    Navigator.pop(context, dollars * 100);
  }

  @override
  Widget build(BuildContext context) {
    final l = L.of(context);
    return AlertDialog(
      key: const Key('open-shift-prompt'),
      title: Text(l.openShiftPromptTitle),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(l.openShiftPromptBody, style: T.text(size: 16)),
            const SizedBox(height: 16),
            TextField(
              key: const Key('open-shift-float'),
              controller: _float,
              autofocus: true,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              style: T.price(),
              decoration: InputDecoration(labelText: l.openingFloat),
              onSubmitted: (_) => _submit(),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(l.notNow),
        ),
        FilledButton(
          key: const Key('open-shift-confirm'),
          onPressed: _submit,
          child: Text(l.openShift),
        ),
      ],
    );
  }
}
