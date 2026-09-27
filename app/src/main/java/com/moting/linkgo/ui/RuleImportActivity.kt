package com.moting.linkgo.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.content.Intent
import com.moting.linkgo.MainActivity

/**
 * 专门负责“导入规则”的跳板 Activity
 * 职责：接收外部分享的文本或文件，初步验证后转发给主页进行深度解析和导入
 */
class RuleImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val action = intent.action
        
        if (Intent.ACTION_SEND == action) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            }
            
            if (uri != null) {
                handleUri(uri)
            } else {
                // 可能是分享的纯文本
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (text != null) {
                    forwardToMain(text)
                } else {
                    android.widget.Toast.makeText(this, "分享内容为空", android.widget.Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } else if (Intent.ACTION_VIEW == action) {
            intent.data?.let { handleUri(it) } ?: run {
                android.widget.Toast.makeText(this, "无效的视图数据", android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    private fun handleUri(uri: android.net.Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.use { 
                it.bufferedReader().readText() 
            }
            if (!content.isNullOrBlank()) {
                forwardToMain(content)
            } else {
                Toast.makeText(this, "文件内容为空或无法读取", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "文件读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun forwardToMain(content: String) {
        val importIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("FORCE_IMPORT_TEXT", content)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(importIntent)
        finish()
    }
}
