var fs = require("fs");
var glob = require("glob");
var path = require("path");

var ignoreFolders = { ignore: ["node_modules/**", "**/build/**"] };
var buildGradlePath = path.join("android", "app", "build.gradle");
var manifestPath = glob.sync("**/AndroidManifest.xml", ignoreFolders)[0];

var askForPublicKey = require('../../askForPublicKey');

var xml2js = require('xml2js');

module.exports = function(){

    console.log('Linking android app...');

    function findMainApplication() {
        if (!manifestPath) {
            return null;
        }

        var manifest = fs.readFileSync(manifestPath, "utf8");

        // Android manifest must include single 'application' element
        var matchResult = manifest.match(/application\s+android:name\s*=\s*"(.*?)"/);
        if (matchResult) {
            var appName = matchResult[1];
        } else {
            return null;
        }
        
        var nameParts = appName.split('.');
        var searchPath = glob.sync("**/" + nameParts[nameParts.length - 1] + ".java", ignoreFolders)[0];
        return searchPath;
    }

    var mainApplicationPath = findMainApplication() || glob.sync("**/MainApplication.java", ignoreFolders)[0];

    // 1. Add the getJSBundleFile override
    var getJSBundleFileOverride = `
        @Override
        protected String getJSBundleFile() {
        return CodePush.getJSBundleFile();
        }
    `;

    function isAlreadyOverridden(codeContents) {
        return /@Override\s*\n\s*protected String getJSBundleFile\(\)\s*\{[\s\S]*?\}/.test(codeContents);
    }

    if (mainApplicationPath) {
        var mainApplicationContents = fs.readFileSync(mainApplicationPath, "utf8");
        if (isAlreadyOverridden(mainApplicationContents)) {
            console.log(`"getJSBundleFile" is already overridden`);
        } else {
            var reactNativeHostInstantiation = "new ReactNativeHost(this) {";
            mainApplicationContents = mainApplicationContents.replace(reactNativeHostInstantiation,
                `${reactNativeHostInstantiation}\n${getJSBundleFileOverride}`);
            fs.writeFileSync(mainApplicationPath, mainApplicationContents);
        }
    } else {
        var mainActivityPath = glob.sync("**/MainActivity.java", ignoreFolders)[0];
        if (mainActivityPath) {
            var mainActivityContents = fs.readFileSync(mainActivityPath, "utf8");
            if (isAlreadyOverridden(mainActivityContents)) {
                console.log(`"getJSBundleFile" is already overridden`);
            } else {
                var mainActivityClassDeclaration = "public class MainActivity extends ReactActivity {";
                mainActivityContents = mainActivityContents.replace(mainActivityClassDeclaration,
                    `${mainActivityClassDeclaration}\n${getJSBundleFileOverride}`);
                fs.writeFileSync(mainActivityPath, mainActivityContents);
            }
        } else {
            console.error(`Couldn't find Android application entry point. You might need to update it manually. \
    Please refer to plugin configuration section for Android at \
    https://github.com/microsoft/react-native-code-push#plugin-configuration-android for more details`);
        }
    }

    if (!fs.existsSync(buildGradlePath)) {
        console.error(`Couldn't find build.gradle file. You might need to update it manually. \
    Please refer to plugin installation section for Android at \
    https://github.com/microsoft/react-native-code-push#plugin-installation-android---manual`);
        return Promise.reject();
    }

    // 2. Add the codepush.gradle build task definitions
    var buildGradleContents = fs.readFileSync(buildGradlePath, "utf8");
    var reactGradleLink = buildGradleContents.match(/\napply from: ["'].*?react\.gradle["']/)[0];
    var codePushGradleLink = `apply from: "../../node_modules/react-native-code-push/android/codepush.gradle"`;
    if (~buildGradleContents.indexOf(codePushGradleLink)) {
        console.log(`"codepush.gradle" is already linked in the build definition`);
    } else {
        buildGradleContents = buildGradleContents.replace(reactGradleLink,
            `${reactGradleLink}\n${codePushGradleLink}`);
        fs.writeFileSync(buildGradlePath, buildGradleContents);
    }

    // 3. Add CodePushPublicKey to strings.xml

    var resourceFilePath = glob.sync("**/src/main/res/values/strings.xml");
    if (resourceFilePath.length === 0) {
        console.log("Couldn't find strings.xml file. You might need to create it manually. Please refer to code signing configuration section for Android");
        return Promise.resolve();
    }
    
    resourceFilePath = resourceFilePath[0];

    var resourceFileContent = fs.readFileSync(resourceFilePath);

    xml2js.parseString(resourceFileContent, (err, resourceFile) => {
        if (err) {
            return Promise.reject();
        }

        if(!resourceFile.resources){
            return Promise.reject(new Error(`Couldn't find <resources> section in resource file from ${resourceFilePath}. Please fix it.`));
        }

        if(!resourceFile.resources.string){
            resourceFile.resources.string = [];
        }

        let parameterIndex = resourceFile.resources.string.findIndex((elem, index) => {
            if (elem.$.name && elem.$.name === "CodePushPublicKey") {
                return true;
            }
        });
        
        //console.log(require("util").inspect(resourceFile.resources.string));

        if (parameterIndex > -1) {
            console.log(`"CodePushPublicKey" already specified in the strings.xml file.`);
            injectCodePushPublicKeyIntoConstructor();
            return Promise.resolve();
        }
        
        return askForPublicKey().then((publicKeyContent) => {
            resourceFile.resources.string.push({
                $: {
                    name: "CodePushPublicKey",
                },
                _: publicKeyContent
            });

            let builder = new xml2js.Builder();
            let xml = builder.buildObject(resourceFile);

            fs.writeFileSync(resourceFilePath, xml);

            injectCodePushPublicKeyIntoConstructor();

            return Promise.resolve();
        });
    });

    // 4. Inject CodePushPublicKey into constructor
    function injectCodePushPublicKeyIntoConstructor(){
        
    }
}