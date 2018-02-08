package com.microsoft.codepush.common.utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.microsoft.codepush.common.testutils.CommonFileTestUtils.getRealNamedFileWithContent;
import static com.microsoft.codepush.common.testutils.CommonFileTestUtils.getRealTestFolder;
import static com.microsoft.codepush.common.testutils.CommonFileTestUtils.getTestingDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UpdateUtilsAndroidTests {

    private FileUtils mFileUtils;
    private CodePushUpdateUtils mUpdateUtils;

    private File createDiffManifestFile(String content) throws IOException {
        return getRealNamedFileWithContent("diff.json", content);
    }

    private final static Map<String, String> CURRENT_PACKAGE_INFO = new HashMap<String, String>() {{
        put("/index.html", "htmlContent");
        put("/index.js", "jsContent");
        put("/index.css", "cssContent");
    }};

    @Before
    public void setUp() {
        mFileUtils = FileUtils.getInstance();
        CodePushUtils utils = CodePushUtils.getInstance(mFileUtils);
        mUpdateUtils = CodePushUpdateUtils.getInstance(mFileUtils, utils);
    }

    @Test
    public void copyNecessaryFilesFromCurrentPackageForFirstUpdate() throws Exception {

        /* This is the first update so diff manifest is empty */
        String diffManifest = "{}";
        File diffManifestFile = createDiffManifestFile(diffManifest);

        /* Add some files in current update, it could be js, css etc */
        File currentPackagePath = getRealTestFolder();
        for (Map.Entry<String, String> entry : CURRENT_PACKAGE_INFO.entrySet()) {
            getRealNamedFileWithContent(currentPackagePath.getAbsolutePath() + entry.getKey(), entry.getValue());
        }

        File newPackagePath = getRealTestFolder();

        mUpdateUtils.copyNecessaryFilesFromCurrentPackage(
                diffManifestFile.getAbsolutePath(),
                currentPackagePath.getAbsolutePath(),
                newPackagePath.getAbsolutePath()
        );

        /* Verify that all files were copied to new package location */
        for (Map.Entry<String, String> entry : CURRENT_PACKAGE_INFO.entrySet()) {
            String fileName = entry.getKey();

            /* Check that file was copied */
            File newPackageFile = new File(newPackagePath.getAbsolutePath(), fileName);
            assertTrue(newPackageFile.exists());

            /* Check that it's content is identical to original */
            String oldFileContent = entry.getValue();
            String newFileContent = mFileUtils.readFileToString(newPackageFile.getAbsolutePath());
            assertEquals(oldFileContent, newFileContent);
        }
    }

    /**
     * After running tests on file, we must delete all the created folders.
     */
    @After
    public void tearDown() throws Exception {
        File testFolder = getTestingDirectory();
        testFolder.delete();
    }
}
