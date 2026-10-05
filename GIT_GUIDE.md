# تطوير السكرتير واسترجاع الإصدارات

المستودع الرسمي: https://github.com/amroquqa99-art/secretary

## نقاط الرجوع

- `release/v0.3.0`: مصدر المرحلة الثالثة قبل إضافة السكرتير المحلي.
- `release/v0.4.0`: السكرتير المحلي بقواعد واضحة وذاكرة وبوابة تأكيد؛ 48 اختبار Android ناجحاً.
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

ثبّت JDK 17 وAndroid SDK platform 37.0 وBuild Tools 36.0.0. اضبط ANDROID_HOME أو local.properties محلياً. الملف gradlew ينزّل Gradle 9.6.0 تلقائياً.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python -m unittest discover -s server -p 'test_*.py'
python checks/verify_migration.py
python checks/verify_v4.py
python checks/verify_v5.py
```

APK الناتج: `app/build/outputs/apk/debug/app-debug.apk`. اقرأ README وRELEASE_0_4_0 وFINAL_RELEASE_ROADMAP للتفاصيل وحدود الاختبارات.

## استرجاع بياناتك الشخصية

Git يحفظ المصدر والاختبارات والتوثيق، ولا يحفظ قاعدة بيانات هاتفك. صدّر النسخة الاحتياطية المشفرة من إعدادات التطبيق واحفظها مع كلمة المرور في مكان آمن. عند تغيير شهادة توقيع التطبيق قد تحتاج لإزالة النسخة السابقة؛ صدّر بياناتك قبل ذلك ثم استوردها بعد التثبيت. لا ترفع كلمات المرور أو قواعد البيانات أو ملفات التوقيع إلى المستودع.

ملفات البناء، APK، بيانات المستخدم، مفاتيح التوقيع وlocal.properties مستبعدة بواسطة .gitignore. النسخة الإنتاجية وتوقيعها واختبارات الهاتف الفعلي ما زالت ضمن العمل المتبقي.
