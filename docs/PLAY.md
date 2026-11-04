# Google Play — מוכנות

מה כבר בקוד, מה מוכן להדבקה בקונסולה, ומה נשאר לבעלים. ה-AAB עצמו (`bundlePlayRelease`) — עוד לא; רק כשמחליטים לעלות.

## בקוד ✅
| דרישה | איפה |
|---|---|
| בלי עדכון עצמי ב-build של Play | flavor `play` (app + wear): `SELF_UPDATE=false`, אין "בדוק עדכונים", `REQUEST_INSTALL_PACKAGES` מוסר (`src/play/AndroidManifest.xml`) |
| מחיקת חשבון באפליקציה | הגדרות ← חשבון ← מחיקת החשבון (`deleteAccount` ב-`functions/account.js`) |
| מחיקת חשבון באתר | https://noted-ce451.web.app/delete-account |
| מדיניות פרטיות | https://noted-ce451.web.app/privacy (עברית + אנגלית) |
| גילוי בולט למיקום ברקע | מסך ההרשאות של תזכורת מקום (`PlacePermission.kt`) — לפני בקשת ההרשאה, עם הנוסח שגוגל דורשת |
| זמן מדויק | `SCHEDULE_EXACT_ALARM` (לא `USE_EXACT_ALARM`) + מסך הסבר — מותר לאפליקציית תזכורות |
| תמונות | דרך Photo Picker — בלי `READ_MEDIA_IMAGES` |
| target SDK | 36 |
| שעון | `wear/` עם `uses-feature watch`, `standalone=false`, flavor `play` |

## build ל-Play (כשמחליטים)
- `./gradlew :app:bundlePlayRelease :wear:bundlePlayRelease -PversionCode=<n>` — נוסיף ל-CI כ-job ידני שמעלה ל-Play דרך Play Developer API (סוד `PLAY_SERVICE_ACCOUNT`).
- חתימה: Play App Signing. ה-keystore הקיים (`KEYSTORE_BASE64`) = **upload key**. גוגל מחזיקה את מפתח ההפצה.
- אחרי ההרשמה ל-Play App Signing: להוסיף את טביעת ה-SHA-1/SHA-256 של **מפתח ההפצה של גוגל** ל-Firebase (Google sign-in) ולמפתח Maps — אחרת התחברות ומפה לא יעבדו בגרסת Play.
- versionCode: אותו מונה של ה-CI; גרסת Play וגרסת GitHub לא יכולות להיות מותקנות זו על זו (חתימה שונה).

## Data safety — תשובות לטופס
**האם האפליקציה אוספת או משתפת נתונים?** כן (אוספת). **משתפת עם צד שלישי:** לא (Firebase/Google Cloud הם ספקי שירות — לא נחשב שיתוף).
**הצפנה בתעבורה:** כן. **אפשר לבקש מחיקה:** כן (באפליקציה + באתר).

| סוג נתון | נאסף | חובה/רשות | מטרה |
|---|---|---|---|
| שם, אימייל (Personal info) | כן | רשות (רק עם התחברות) | ניהול חשבון, פונקציונליות (שיתוף) |
| תמונות (Photos) | כן | רשות | פונקציונליות (תמונות בפתק, מסונכרן) |
| הקלטות קול (Audio → Voice or sound recordings) | כן | רשות | פונקציונליות |
| קבצים ומסמכים / תוכן אחר שהמשתמש יצר (Other user-generated content) | כן | רשות | פונקציונליות |
| מיקום מדויק (Precise location) | **לא נאסף** — נשאר במכשיר (גדרות). חיפוש מקום שולח טקסט + מיקום משוער ל-Places דרך השרת ולא נשמר → לסמן "Approximate location — collected, ephemeral, functionality" | רשות | פונקציונליות |
| מזהי מכשיר (Device or other IDs — FCM token) | כן | חובה למשתמש מחובר | פונקציונליות (התראות) |
| אנליטיקה / קריסות / פרסום | לא | — | — |

## הצהרת מיקום ברקע (Permissions declaration)
- **התכונה:** תזכורת לפי מקום — "תזכיר לי לקנות חלב כשאגיע לסופר" / "כשאצא מהבית".
- **למה ברקע:** הגדרות (geofences) של המערכת מזהות הגעה ויציאה כשהאפליקציה סגורה; בלי "לאפשר תמיד" התזכורת לא תקפוץ.
- **סרטון (עד 30 שנ׳):** פתק ← תזכורת ← מקום ← בחירת מקום ← קבע ← מסך ההרשאות עם הגילוי ← "לאפשר תמיד" ← התראה שקופצת בהגעה.

## הצהרת זמן מדויק
לא נדרשת ל-`SCHEDULE_EXACT_ALARM` (המשתמש מאשר בעצמו).

## דף בחנות
טקסטים ב-`docs/store/listing-he.md` ו-`listing-en.md`. גרפיקה (נכין): אייקון 512×512, תמונה ראשית 1024×500, 4–8 צילומי טלפון, 2+ צילומי שעון (מסך עגול 384×384).

## מה נשאר לבעלים (ירוכז בהודעה אחת כשמתחילים שלב 11)
1. חשבון Play Console (25$) + אימות זהות.
2. בדיקה סגורה: 12 בודקים × 14 יום רצופים (חשבון אישי חדש) — לפני production.
3. יצירת service account עם הרשאת "Release manager" → סוד `PLAY_SERVICE_ACCOUNT`.
4. מילוי Data safety / content rating / קהל יעד (לפי הטבלאות כאן).
5. (רשות) אימייל ליצירת קשר בדף הבחנות ובמדיניות הפרטיות — כרגע הקישור הוא ל-GitHub Issues.
