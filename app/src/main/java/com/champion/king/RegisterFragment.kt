package com.champion.king

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.widget.doOnTextChanged
import com.champion.king.core.config.AppConfig
import com.champion.king.core.ui.BaseBindingFragment
import com.champion.king.data.AuthRepository
import com.champion.king.databinding.FragmentRegisterBinding
import com.champion.king.util.*
import com.google.firebase.database.FirebaseDatabase

class RegisterFragment : BaseBindingFragment<FragmentRegisterBinding>() {

    private val repo by lazy {
        AuthRepository(FirebaseDatabase.getInstance(AppConfig.DB_URL).reference)
    }

    private val cityDistricts = mapOf(
        "臺北市" to listOf("中正區","大同區","中山區","松山區","大安區","萬華區","信義區","士林區","北投區","內湖區","南港區","文山區"),
        "新北市" to listOf("板橋區","新莊區","中和區","永和區","土城區","樹林區","三峽區","鶯歌區","淡水區","三重區","蘆洲區","五股區","泰山區","林口區","八里區","深坑區","石碇區","坪林區","三芝區","金山區","萬里區","烏來區"),
        "桃園市" to listOf("桃園區","中壢區","平鎮區","八德區","楊梅區","蘆竹區","大溪區","龍潭區","龜山區","大園區","新屋區","觀音區","復興區"),
        "臺中市" to listOf("中區","東區","南區","西區","北區","西屯區","南屯區","北屯區","豐原區","大里區","太平區","潭子區","大雅區","霧峰區","清水區","沙鹿區","龍井區","梧棲區","大甲區","外埔區","后里區"),
        "臺南市" to listOf("中西區","東區","南區","北區","安平區","安南區","永康區","仁德區","歸仁區","新營區","善化區","新化區","佳里區","麻豆區"),
        "高雄市" to listOf("苓雅區","新興區","前金區","鹽埕區","鼓山區","旗津區","三民區","左營區","楠梓區","小港區","鳳山區","前鎮區","岡山區","橋頭區","路竹區"),
        "基隆市" to listOf("仁愛區","信義區","中正區","中山區","安樂區","暖暖區","七堵區"),
        "新竹市" to listOf("東區","北區","香山區"),
        "嘉義市" to listOf("東區","西區"),
        "宜蘭縣" to listOf("宜蘭市","羅東鎮","蘇澳鎮"),
        "花蓮縣" to listOf("花蓮市","吉安鄉","壽豐鄉"),
        "臺東縣" to listOf("臺東市","卑南鄉","成功鎮"),
        "澎湖縣" to listOf("馬公市","湖西鄉","白沙鄉"),
        "金門縣" to listOf("金城鎮","金湖鎮","金沙鎮"),
        "連江縣" to listOf("南竿鄉","北竿鄉","東引鄉")
    )

    override fun createBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentRegisterBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCityDistrictSpinners()
        setupValidation()
        binding.buttonRegister.setThrottledClick { tryRegister() }
    }

    private fun setupCityDistrictSpinners() {
        val cityList = cityDistricts.keys.toList()
        val cityAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, cityList)
        binding.spinnerCity.adapter = cityAdapter

        // 當縣市選取變更時，更新行政區
        binding.spinnerCity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedCity = cityList[position]
                val districts = cityDistricts[selectedCity] ?: emptyList()
                val districtAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, districts)
                binding.spinnerDistrict.adapter = districtAdapter

                // 若縣市為臺南市，預設選永康區
                if (selectedCity == "臺南市") {
                    val defaultDistrictIndex = districts.indexOf("永康區")
                    if (defaultDistrictIndex >= 0) {
                        binding.spinnerDistrict.setSelection(defaultDistrictIndex)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 🔹 預設選臺南市
        val defaultCityIndex = cityList.indexOf("臺南市")
        if (defaultCityIndex >= 0) {
            binding.spinnerCity.setSelection(defaultCityIndex)
        }
    }

    /**
     * 設定即時驗證 - 使用右側圖標提示
     */
    private fun setupValidation() {
        // 帳號即時驗證
        binding.editTextAccount.doOnTextChanged { text, _, _, _ ->
            val account = text?.toString()?.trim().orEmpty()

            when {
                account.isEmpty() -> {
                    binding.iconAccountStatus.visibility = View.GONE
                }
                !ValidationRules.isValidAccount(account) -> {
                    binding.iconAccountStatus.setImageResource(android.R.drawable.ic_delete)
                    binding.iconAccountStatus.visibility = View.VISIBLE
                    binding.editTextAccount.error = AppConfig.Msg.ERR_ACCOUNT_RULE
                }
                else -> {
                    // 檢查帳號是否已存在
                    checkAccountAvailability(account)
                }
            }
        }

        // 密碼即時驗證
        binding.editTextPassword.doOnTextChanged { text, _, _, _ ->
            val password = text?.toString()?.trim().orEmpty()

            when {
                password.isEmpty() -> {
                    binding.iconPasswordStatus.visibility = View.GONE
                }
                !ValidationRules.isValidPasswordLen(password) -> {
                    binding.iconPasswordStatus.setImageResource(android.R.drawable.ic_delete)
                    binding.iconPasswordStatus.visibility = View.VISIBLE
                    binding.editTextPassword.error = AppConfig.Msg.ERR_PASSWORD_LEN
                }
                else -> {
                    binding.iconPasswordStatus.setImageResource(R.drawable.ic_check_green)
                    binding.iconPasswordStatus.visibility = View.VISIBLE
                    binding.editTextPassword.error = null
                }
            }
        }

        // Email 即時驗證
        binding.editTextEmail.doOnTextChanged { text, _, _, _ ->
            val email = text?.toString()?.trim().orEmpty()

            when {
                email.isEmpty() -> {
                    binding.iconEmailStatus.visibility = View.GONE
                }
                !ValidationRules.isValidEmail(email) -> {
                    binding.iconEmailStatus.setImageResource(android.R.drawable.ic_delete)
                    binding.iconEmailStatus.visibility = View.VISIBLE
                    binding.editTextEmail.error = "請輸入正確 Email"
                }
                else -> {
                    binding.iconEmailStatus.setImageResource(R.drawable.ic_check_green)
                    binding.iconEmailStatus.visibility = View.VISIBLE
                    binding.editTextEmail.error = null
                }
            }
        }

        // 手機即時驗證
        binding.editTextPhone.doOnTextChanged { text, _, _, _ ->
            val phone = text?.toString()?.trim().orEmpty()

            when {
                phone.isEmpty() -> {
                    binding.iconPhoneStatus.visibility = View.GONE
                }
                !ValidationRules.isValidPhone(phone) -> {
                    binding.iconPhoneStatus.setImageResource(android.R.drawable.ic_delete)
                    binding.iconPhoneStatus.visibility = View.VISIBLE
                    binding.editTextPhone.error = "手機需為 09 開頭共 10 碼"
                }
                else -> {
                    binding.iconPhoneStatus.setImageResource(R.drawable.ic_check_green)
                    binding.iconPhoneStatus.visibility = View.VISIBLE
                    binding.editTextPhone.error = null
                }
            }
        }
    }

    /**
     * 檢查帳號是否已存在
     */
    private fun checkAccountAvailability(account: String) {
        FirebaseDatabase.getInstance(AppConfig.DB_URL).reference
            .child("users")
            .child(account)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    // 帳號已存在
                    binding.iconAccountStatus.setImageResource(android.R.drawable.ic_delete)
                    binding.iconAccountStatus.visibility = View.VISIBLE
                    binding.editTextAccount.error = "此帳號已被使用"
                } else {
                    // 帳號可用
                    binding.iconAccountStatus.setImageResource(R.drawable.ic_check_green)
                    binding.iconAccountStatus.visibility = View.VISIBLE
                    binding.editTextAccount.error = null
                }
            }
            .addOnFailureListener {
                binding.iconAccountStatus.visibility = View.GONE
            }
    }

    private fun tryRegister() = requireContext().guardOnline {
        val city = binding.spinnerCity.selectedItem?.toString()?.trim().orEmpty()
        val district = binding.spinnerDistrict.selectedItem?.toString()?.trim().orEmpty()

        if (city.isEmpty()) { toast("請選擇縣市"); return@guardOnline }
        if (district.isEmpty()) { toast("請選擇行政區"); return@guardOnline }

        val account = binding.editTextAccount.text.toString().trim()
        val password = binding.editTextPassword.text.toString().trim()
        val email = binding.editTextEmail.text.toString().trim()
        val phone = binding.editTextPhone.text.toString().trim()
        val deviceNum = binding.editTextDevicePassword.text.toString().trim()

        // 最終驗證
        if (account.isEmpty()) {
            binding.editTextAccount.error = "請輸入帳號"
            return@guardOnline
        }
        if (!ValidationRules.isValidAccount(account)) {
            binding.editTextAccount.error = AppConfig.Msg.ERR_ACCOUNT_RULE
            return@guardOnline
        }
        if (password.isEmpty()) {
            binding.editTextPassword.error = "請輸入密碼"
            return@guardOnline
        }
        if (!ValidationRules.isValidPasswordLen(password)) {
            binding.editTextPassword.error = AppConfig.Msg.ERR_PASSWORD_LEN
            return@guardOnline
        }
        if (email.isEmpty()) {
            binding.editTextEmail.error = "請輸入 Email"
            return@guardOnline
        }
        if (!ValidationRules.isValidEmail(email)) {
            binding.editTextEmail.error = "請輸入正確 Email"
            return@guardOnline
        }
        if (phone.isEmpty()) {
            binding.editTextPhone.error = "請輸入手機"
            return@guardOnline
        }
        if (!ValidationRules.isValidPhone(phone)) {
            binding.editTextPhone.error = "手機需為 09 開頭共 10 碼"
            return@guardOnline
        }
        if (deviceNum.isEmpty()) {
            binding.editTextDevicePassword.error = "請輸入授權碼"
            return@guardOnline
        }

        // 🔹 註冊時，accountStatus、lineId、remark 使用預設值
        // accountStatus = "INACTIVE" (未開通)
        // lineId = ""
        // remark = ""
        // 這些欄位會在 AuthRepository.registerUser 中自動設定預設值

        repo.registerUser(account, password, email, phone, city, district, deviceNum) { ok, msg ->
            if (ok) {
                toast("註冊成功！")
                parentFragmentManager.popBackStack()
            } else toast("註冊失敗：${msg ?: "未知錯誤"}")
        }
    }
}