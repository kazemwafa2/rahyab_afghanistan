# امضای APK

بدون یک کلید ثابت، کاربران نمی‌توانند نسخه جدید را روی نسخه قبلی نصب کنند.

1. ساخت کلید (یک بار، روی هر کمپیوتر یا Termux):
   `keytool -genkeypair -v -keystore release.jks -alias rahyab -keyalg RSA -keysize 4096 -validity 10000`
2. تبدیل به متن: `base64 -w0 release.jks`
3. در GitHub: Settings → Secrets and variables → Actions، این چهار مورد را بسازید:
   `KEYSTORE_BASE64`، `KEYSTORE_PASSWORD`، `KEY_ALIAS`، `KEY_PASSWORD`
4. فایل `release.jks` و رمزها را جای امن نگه دارید و هرگز در مخزن نگذارید. اگر گم شود نمی‌توانید برنامه را به‌روزرسانی کنید.
