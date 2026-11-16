# بازضبط بسته‌های صوتی (edge-tts عصبی)

بسته‌های صوتی داخل `app/src/main/assets/voice/` با موتور عصبی Azure (edge-tts)
ساخته می‌شوند. این اسکریپت‌ها برای بازضبط یا افزودن گویندهٔ تازه اند:

```
python3 extract_voice_texts.py   # استخراج ۹۲ متن هر سه زبان از منابع رشته‌ها → voice_texts.json
pip install edge-tts
python3 gen_voice.py latifa      # بازضبط یک بسته (resume-safe — فایل ساخته‌شده را می‌پرد)
python3 gen_voice.py sahar       # گویندهٔ چندزبانه (fa+ps+en) — خروجی در scripts/voice_staging/
```

خروجی staging سپس به `app/src/main/assets/voice/` کپی می‌شود (بسته‌های چندزبانه
زیرپوشهٔ `fa/ps/en` دارند؛ chime.ogg در ریشهٔ بسته). مشخصات هر گوینده (صدا، سرعت،
زیر و بمی) در `PACKS` داخل `gen_voice.py` تنظیم می‌شود.

* «لطیفه» در نسخهٔ ۱٫۱٫۰ بازضبط شد: بستهٔ پیشین سرعت +۲۵٪ و بلندی +۱۰dB ناسازگار داشت.
