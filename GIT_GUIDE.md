# تطوير السكرتير واسترجاع الإصدارات

المستودع الرسمي: https://github.com/amroquqa99-art/secretary

## نقاط الرجوع

- `release/v0.3.0`: مصدر المرحلة الثالثة قبل إضافة السكرتير المحلي.
- `release/v0.4.0`: السكرتير المحلي بقواعد واضحة وذاكرة وبوابة تأكيد؛ 48 اختبار Android ناجحاً.
- `release/v0.5.0`: هرم الأهداف، المراجعة وخطة الأسبوع والصوت المحلي داخل الشاشة.
- `main`: أحدث تطوير. نقاط الرجوع فروع ثابتة؛ ليست Git tags.

للتطوير:

```sh
git clone https://github.com/amroquqa99-art/secretary.git
cd secretary
git switch -c feature/my-change
```

لمراجعة إصدار قديم دون تعديل main:

```sh
git switch --detach release/v0.3.0
```

للإصلاح ابدأ فرعاً من الإصدار المطلوب، أو استخدم `git revert COMMIT_SHA` لعكس تغيير مع الاحتفاظ بالتاريخ. لا تستخدم force push على main.

## البناء والتحقق

ثبّت JDK 21 وAndroid SDK platform 37.0 وBuild Tools 36.0.0 وNDK 30.0.16248370 وCMake 3.22.1. اضبط ANDROID_HOME أو local.properties محلياً. الملف gradlew ينزّل Gradle 9.6.0 تلقائياً.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python -m unittest discover -s server -p 'test_*.py'
python checks/verify_migration.py
python checks/verify_v4.py
python checks/verify_v5.py
```

APK الناتج: `app/build/outputs/apk/debug/app-debug.apk`. اقرأ README وRELEASE_0_7_0 وFINAL_RELEASE_ROADMAP للتفاصيل وحدود الاختبارات.

## استرجاع بياناتك الشخصية

Git يحفظ المصدر والاختبارات والتوثيق، ولا يحفظ قاعدة بيانات هاتفك. صدّر النسخة الاحتياطية المشفرة من إعدادات التطبيق واحفظها مع كلمة المرور في مكان آمن. عند تغيير شهادة توقيع التطبيق قد تحتاج لإزالة النسخة السابقة؛ صدّر بياناتك قبل ذلك ثم استوردها بعد التثبيت. لا ترفع كلمات المرور أو قواعد البيانات أو ملفات التوقيع إلى المستودع.

ملفات البناء، APK، بيانات المستخدم، مفاتيح التوقيع وlocal.properties مستبعدة بواسطة .gitignore. النسخة الإنتاجية وتوقيعها واختبارات الهاتف الفعلي ما زالت ضمن العمل المتبقي.
## متابعة النموذج المحلي 0.6.0

فرع العمل: `feature/local-model-v0.6`. وصف النموذج وحالته في `LOCAL_MODELS.md`، والنتائج الفعلية في `checks/local_model_smoke_results.json`. الأوزان وملفات البناء والمفاتيح ليست في Git.

مفتاح debug الحالي محفوظ منفصلاً باسم `secretary-debug-signing-0.6.keystore`. لاستمرار شهادة التجربة بعد استبدال بيئة البناء، استعد هذا الملف إلى مسار مفتاح Android debug الافتراضي قبل البناء؛ لا تستبدل مفتاح مشروع آخر دون حفظه. بيانات debug الافتراضية: alias `androiddebugkey` وكلمة المرور `android`. لا تستخدم مفتاح debug للنشر الإنتاجي.

المفتاح الجديد مختلف عن 0.5.0؛ تحديث الهاتف من النسخ السابقة قد يتطلب تصدير نسخة مشفرة ثم إعادة التثبيت والاستعادة. تظل فروع الإصدارات السابقة محفوظة للرجوع إلى المصدر.

## محرك GGUF في 0.7.0

فرع التطوير: `feature/gguf-v0.7`، ومرجع الإصدار: `release/v0.7.0`. مصدر llama.cpp ينزل عند البناء بمراجعة مثبتة وبصمة SHA-256؛ `SECRETARY_LLAMA_CPP_DIR` اختياري لنسخة محلية من المراجعة نفسها. لا تحفظ NDK أو مصدر الطرف الثالث أو أوزان GGUF أو مجلدات `.cxx` و`build` داخل Git. نتائج القياس الفعلية في `checks/gguf_smoke_results.json`، وحدود العربية في `LOCAL_MODELS.md`.

توقيع 0.7.0 هو نفسه 0.6.0. مفتاح debug المحفوظ يبقى للتجربة، ولا يعد مفتاح إصدار إنتاجي. Git يرجع المصدر؛ بيانات هاتفك ترجع من النسخة المشفرة الشخصية.
