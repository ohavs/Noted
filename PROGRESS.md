# PROGRESS — noted

## Now
- שלב 0 (אפיון) ✅. שלב 1 (אב-טיפוס HTML) — חי ב-https://noted-ce451.web.app, ממשיך לקבל תיקונים במקביל.
  - 2026-11-01 תיקונים: מחיקת בלוק (לחיצה על ידית ⠿ → למעלה/למטה/שכפול/מחיקה + ביטול בטוסט; Backspace בתחילת שורה אחרי ציור/תמונה מסמן ואז מוחק), פונטים/accent בהגדרות תוקנו (הקליק נתפס ב-`html[data-theme]`), משיכה למטה סוגרת גיליונות ואת העורך מכל מקום כשהגלילה למעלה, ערימה: כרטיסים שנערמים משאירים פס כותרת (PEEK=56px, עד 3) — לחיצה על כותרת פותחת אותו. משיכה למעלה לעריכה נשארה רק מהידית בתחתית הכרטיס הפתוח (לא מתנגש בגלילה).
- **שלב 2 — שלד Kotlin: נבנה, ממתין ל-CI ירוק + סודות חתימה.** `app/` (Compose, minSdk 26, target 36), Gradle 8.14.3, AGP 8.11.1, Kotlin 2.2.21, KSP 2.2.21-2.0.5, Compose BOM 2025.07.00, Room 2.7.2.
  - מערכת עיצוב `design/`: צבעים (בהיר/כהה, 4 accent, 8 צבעי פתק), 3 צמדי פונטים (TTF משתנים מ-google/fonts), `Motion.touch/glide` (אותם קפיצים), `Haptics`, `Squircle`, `press()`, מתג, טוסט עם ביטול, אייקוני Phosphor (`Ph.kt`, נוצר מ-phosphor-icons/core).
  - Room (`notes`, `categories`, בלוקים כ-JSON), DataStore להגדרות, מסך בית בסיסי (רשימת כרטיסים, קטגוריות, FAB, לחיצה ארוכה → סל + ביטול), הגדרות, "בדוק עדכונים" (`update/Updater.kt`: GitHub Release אחרון → הורדה → מתקין המערכת, כולל הרשאת התקנה).
  - CI: `.github/workflows/android.yml` — APK חתום + GitHub Release `v0.2.<run>` (versionCode = run_number). בלי סודות → APK חתום-debug כ-artifact בלבד.
  - dl.google.com חסום בסביבת הענן → אין build מקומי; מאמתים ב-CI.
- **הבא:** CI ירוק → הבעלים מוסיף `KEYSTORE_BASE64` + `KEYSTORE_PASSWORD` → הרצה מחדש → release ראשון → שלב 3 (מסך בית אמיתי: ערימה + peek כמו באב-טיפוס).

## החלטות מרכזיות (פירוט ב-SPEC)
- אנדרואיד בלבד, Kotlin + Compose, `com.ohav.noted`. Firebase (Google sign-in, Blaze + התראת תקציב $1). Google Maps.
- תנועה: שני קפיצים מרכזיים בלבד — `touch` (k=520, c=25, ζ≈0.55) ו-`glide` (k=230, c=30, ζ≈1). באב-הטיפוס ה-easing של CSS נגזר מהם (`linear()`); אותם ערכים יעברו ל-Compose `spring()`.
- אייקונים: Phosphor (regular + fill לפעיל). Squircle אמיתי דרך `corner-shape` איפה שנתמך.
- Region (שלב 2+): הכי קרוב לישראל ובחינם ככל האפשר — Firestore ב-`me-west1` (תל אביב) אם מכסת החינם חלה שם, Storage בדלי החינמי (`us-central1`/`us-east1`/`us-west1`, חובה למכסת החינם), Functions — לבדוק מכסת חינם ב-`me-west1` מול `europe-west1`. לאמת לפני יצירה (בחירת region בלתי הפיכה).
- בועת הסימון מוצגת **מתחת** לטקסט במכשירי מגע (תפריט המערכת של הדפדפן תופס את המקום שמעל; באפליקציה האמיתית נחליף אותו).

## פעולות ידניות של הבעלים
- 2026-11-01: `FIREBASE_TOKEN` נוסף ✅.
- שלב 2: keystore נוצר (RSA 2048, alias `noted`), SHA-256: `3A:8A:46:7A:9C:0A:22:76:45:2F:A5:DD:F7:88:7F:D7:1E:4F:A1:85:D6:F3:75:58:A2:98:D2:EA:0D:13:FA:A2`. `google-services.json`, sign-in, Maps — בשלבים 7–8.

## סודות שהוגדרו (שמות בלבד)
- `FIREBASE_TOKEN` ✅. נדרשים עכשיו: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` (alias קבוע `noted`, סיסמת מפתח = סיסמת מאגר). בהמשך: `GOOGLE_SERVICES_JSON`, `MAPS_API_KEY`.

## פתוח באב-טיפוס (לא חוסם)
- גרירה של פתק על שם קטגוריה להעברה — לא מומש (יש העברה דרך התפריט).
- תמונות דמו נטענות מ-picsum.photos.
