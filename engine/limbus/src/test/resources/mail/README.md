# Empty mailbox phone preview replay

`empty-mailbox-preview.png` comes from the user's 2026-09-10 screenshot showing
`No mail in storage.` after the 753 mail run stalled. It is **not an original
1280x720 capture from the automation frame source**. The app displayed the game in
a 544x306 preview inside a 588x1278 screenshot, with the mailbox pixels retained.

Reproduction: crop the source to `[22,149,566,455)` (left, top, right, bottom), then
resize that 544x306 image to 1280x720 using OpenCV `INTER_LINEAR`. The bands `y < 80` (host FPS/overlay) and `y >= 600` (account/currency/navigation)
are filled black before publication. The mailbox title, empty message and button
row at `y=515..585` are unchanged; no OCR text or button pixels were synthesized.

SHA-256:

```text
3215c36e0915b1476c71147f0a18c55ae8bff757a9b67e6d152877ac9d316004  original-user-screenshot.png
ca96d2ce244926b72deb0edc1a125b03ad5a7676c1a0803360f3c078d139b9c2  empty-mailbox-preview.png
```

`MailboxNativeTest` takes an OpenCV JNI library, a local LALC resource directory
and this PNG. It runs the production LimbusRecognizer, PP-OCR, mail actions and
upstream mail graph. The upstream exact and pyramid templates both miss the empty
mailbox. Dedicated OCR regions locate Mailbox, the empty message and Close without
guessing a button from an inverse match or lowering OCR confidence.

The replay records the Close touch and then keeps showing the unchanged image:
the engine must report that closing is unconfirmed and must not reach check_mail.
A second replay erases Close: the mailbox remains observed with `close=null`,
and the action fails without input. No post-click device frame was supplied, so
this test does not prove that a phone
accepted the touch, closed the mailbox or collected any mail. The smaller preview
also cannot establish recognition accuracy on the device's original frames.

OCR models/templates are read externally from LALC v5.0.0; none are copied here.
Game pixels remain owned by their respective copyright holders and are retained
only as the supplied issue's regression fixture.
