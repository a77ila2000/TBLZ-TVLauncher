package com.tblz.tvlauncher.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.tblz.tvlauncher.R
import com.tblz.tvlauncher.databinding.DialogSettingsBinding
import com.tblz.tvlauncher.engine.ConfigStore
import com.tblz.tvlauncher.engine.WavveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsDialog(
    context: Context,
    private val onSettingsSaved: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogSettingsBinding
    private var tvingLoginDialog: TvingLoginDialog? = null

    override fun onStop() {
        super.onStop()
        // HOME closes this dialog (MainActivity.onNewIntent); take the TVING login window with it,
        // otherwise it stayed on screen alone.
        tvingLoginDialog?.dismiss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.75).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.92).toInt()
        )

        loadCurrentValues()
        updateStatus()

        // One remote click selects the desired provider in the TVING page.
        val openTvingLogin = { provider: String? ->
            tvingLoginDialog = TvingLoginDialog(context, provider) {
                updateStatus()
                onSettingsSaved()
            }.also { it.show() }
        }
        binding.btnTvingLogin.setOnClickListener { openTvingLogin(null) }
        binding.btnTvingCj.setOnClickListener { openTvingLogin("CJ ONE") }
        binding.btnTvingPhoneLogin.setOnClickListener { openTvingLogin("네이버") }
        binding.btnTvingKakao.setOnClickListener { openTvingLogin("카카오") }
        binding.btnTvingApple.setOnClickListener { openTvingLogin("Apple") }

        binding.btnTvingLogout.setOnClickListener {
            ConfigStore.clearTvingSession()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            updateStatus()
            Toast.makeText(context, "TVING 로그아웃되었습니다.", Toast.LENGTH_SHORT).show()
            onSettingsSaved()
        }

        // Wavve Login
        binding.btnWavveLogin.setOnClickListener {
            val id = binding.etWavveId.text.toString().trim()
            val pw = binding.etWavvePw.text.toString().trim()
            if (id.isEmpty() || pw.isEmpty()) {
                Toast.makeText(context, "Wavve 아이디와 비밀번호를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            ConfigStore.wavveId = id
            ConfigStore.wavvePw = pw

            CoroutineScope(Dispatchers.Main).launch {
                val success = WavveEngine.login(id, pw)
                if (success) {
                    Toast.makeText(context, "Wavve 로그인 성공!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Wavve 로그인 실패: 계정 정보를 확인하세요.", Toast.LENGTH_LONG).show()
                }
                updateStatus()
                onSettingsSaved()
            }
        }

        binding.btnWavveLogout.setOnClickListener {
            ConfigStore.wavveAuthKey = ""
            ConfigStore.wavveDeviceId = ""
            updateStatus()
            Toast.makeText(context, "Wavve 로그아웃되었습니다.", Toast.LENGTH_SHORT).show()
            onSettingsSaved()
        }

        // [ 초기화 ] Action: Confirm and Clear all preferences
        binding.btnReset.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("런처 설정 초기화")
                .setMessage("모든 계정 로그인 정보, 즐겨찾기, 홈 앱 바로가기 설정이 완전히 초기화됩니다. 계속할까요?")
                .setPositiveButton("초기화") { _, _ ->
                    ConfigStore.clearAll()
                    Toast.makeText(context, "런처 설정이 모두 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                    onSettingsSaved()
                    dismiss()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener { saveValues() }
    }

    private fun loadCurrentValues() {
        if (ConfigStore.bootMode == ConfigStore.BOOT_MODE_DIRECT_TV) {
            binding.rbBootDirect.isChecked = true
        } else {
            binding.rbBootHome.isChecked = true
        }

        binding.etWavveId.setText(ConfigStore.wavveId)
        binding.etWavvePw.setText(ConfigStore.wavvePw)
    }

    private fun updateStatus() {
        // TVING Status
        val isTvingLoggedIn = ConfigStore.isTvingSessionReady
        binding.btnTvingLogout.isVisible = isTvingLoggedIn
        if (isTvingLoggedIn) {
            binding.tvTvingStatus.text = "● 로그인됨"
            binding.tvTvingStatus.setTextColor(ContextCompat.getColor(context, R.color.primary))
        } else {
            binding.tvTvingStatus.text = "로그인 안됨"
            binding.tvTvingStatus.setTextColor(ContextCompat.getColor(context, R.color.text_dim))
        }

        // Wavve Status
        val isWavveLoggedIn = ConfigStore.wavveAuthKey.isNotEmpty()
        binding.btnWavveLogout.isVisible = isWavveLoggedIn
        if (isWavveLoggedIn) {
            binding.tvWavveStatus.text = "● 로그인됨"
            binding.tvWavveStatus.setTextColor(ContextCompat.getColor(context, R.color.primary))
        } else {
            binding.tvWavveStatus.text = "로그인 안됨"
            binding.tvWavveStatus.setTextColor(ContextCompat.getColor(context, R.color.text_dim))
        }
    }

    private fun saveValues() {
        ConfigStore.bootMode = if (binding.rbBootDirect.isChecked) {
            ConfigStore.BOOT_MODE_DIRECT_TV
        } else {
            ConfigStore.BOOT_MODE_HOME
        }

        ConfigStore.wavveId = binding.etWavveId.text.toString().trim()
        ConfigStore.wavvePw = binding.etWavvePw.text.toString().trim()

        Toast.makeText(context, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
        onSettingsSaved()
        dismiss()
    }
}
