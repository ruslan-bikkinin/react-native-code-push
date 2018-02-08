package com.microsoft.codepush.common.utils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class UpdateUtilsIsHashIgnoredTest {

    private CodePushUpdateUtils mUpdateUtils;

    @Parameters(name = "{index}: CodePushUpdateUtils.isHashIgnored({0}) = {1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"__MACOSX/path", true},
                {"__MACOSXpath", false},
                {".DS_Store", true},
                {"path/.DS_Store", true},
                {".codepushrelease", true},
                {"path/.codepushrelease", true},
        });
    }

    @Parameter
    public String path;

    @Parameter(1)
    public boolean expected;

    @Before
    public void setUp() {
        FileUtils fileUtils = FileUtils.getInstance();
        CodePushUtils utils = CodePushUtils.getInstance(fileUtils);
        mUpdateUtils = CodePushUpdateUtils.getInstance(fileUtils, utils);
    }

    @Test
    public void testFinalizeResources() {
        assertEquals(expected, mUpdateUtils.isHashIgnored(path));
    }
}
