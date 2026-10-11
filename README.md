# Deep Sea Diver BOT

## APK banane ka tarika (GitHub Actions)
1. GitHub par repo kholo, is zip ke andar ka sab kuch (build.gradle.kts, settings.gradle.kts, app/, .github/ ...) upload karo (zip ko extract karke files upload karna).
2. Actions tab -> "Build APK" chalne do (3-5 min). Phir Releases me `app-debug.apk` milegi.
3. APK install karo.

(Android Studio se bhi khul jata hai: Open -> yeh folder -> Run.)

## Chalane ka tarika
1. BOT app kholo -> "Accessibility settings kholo" -> Deep Sea Diver BOT ON karo.
   (Android 13/14 me agar "Restricted setting" aaye: Settings > Apps > Deep Sea Diver BOT > 3 dots > Allow restricted settings.)
2. "Screen capture allow karo" -> Start now.
3. "Level save karo": game ke menu me jo LEVEL N likha hai wahi number daalo (abhi 2).
4. Game kholo. Neeche-right me floating START dikhega -> dabao. BOT khud:
   - LEVEL button dabata hai
   - hold + left/right drag karke fish se bachta hai (level 1-40 slow, 40 ke baad fast mode)
   - Level Complete par sirf CLAIM dabata hai (CLAIM 2X kabhi nahi), phir CONTINUE
   - ads aaye to close (X) dabata hai, Play Store/browser khul jaye to BACK
5. Rokne ke liye wahi button (STOP).

## Agar kuch atak jaye
Status text (START ke upar) me "?" aur seconds dikhein to 7 sec baad ek screenshot
`Android/data/com.deepdiver.bot/files/unknown/` me save hota hai - wo mujhe bhej do.
