#/bin/bash

cd coverageTestApp
if [ ! -d "node_modules" ]; then
    npm i
fi;

rm -rf node_modules/react-native-code-push/android
cp -R ../android node_modules/react-native-code-push/android
rsync -r --exclude-from="../coverageCopyIgnore.txt" ../android node_modules/react-native-code-push/android 

cd android
./gradlew :react-native-code-push:build
./gradlew :react-native-code-push:coverageReport

cd ..

echo "Coverage report results: file://$PWD/node_modules/react-native-code-push/android/app/build/reports/jacoco/coverageReport/html/index.html"