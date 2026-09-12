package com.cloudwubi.ime;

import android.app.Activity;
import android.os.Bundle;

/**
 * CloudWubi 应用信息界面（v0.4.4）
 * 版本号/版权/作者/联系方式统一收纳于此（不再占用输入界面）
 */
public class AboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
    }
}
