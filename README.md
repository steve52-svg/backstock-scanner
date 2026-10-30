# Back Stock Scanner (no AI)

An Android app that reads the frozen walk-in's case boxes **on the phone, with no internet and no AI service**.

- **Scan** — take a picture of a few boxes, or **Scan photos** to read pictures you already took (for example the Walk-in Camera's route photos).
- It reads barcodes and label text (printed and hand-printed) with Google ML Kit's on-phone readers, then matches what it read to the product list with spelling-tolerant matching: full names, the short names written on boxes ("Lamb Vind", "Cauli Pizz", "MOC Bowl"…), and SKUs.
- **Green** = sure, **yellow** = a guess, **gray** = text it couldn't place. Tap any label to pick the right product, search, or add a new one.
- **It learns:** every correction is remembered, so the same writing is recognized next time, and the other labels are re-checked right away. Barcodes can be taught too. "Not a product label" makes it skip printing like KEEP FROZEN.
- Handwritten labels you confirm are saved to `Pictures/BackStockScanner/Training/<product>/` — the training set for a small phone model later.
- **Found list** — everything seen today, in walk-in order (left shelf → back wall → right shelf → front wall), with Share.

No auto pulls and no QR codes, by design.

The product list (`app/src/main/assets/catalog.json`) comes from the box library in the Frozen Pull List.

Every push to `main` builds `BackStockScanner.apk` with GitHub Actions and publishes it as the **latest** release.
