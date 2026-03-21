package com.example.mytabletime

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log // 解决 Log 报错
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import okhttp3.*
import org.jsoup.Jsoup
import java.io.IOException
import java.nio.charset.Charset
import java.util.*
import com.google.gson.Gson // 解决 Gson 报错
import com.google.gson.reflect.TypeToken // 解决 type 报错
import kotlin.collections.HashMap
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.RelativeLayout
import android.content.res.ColorStateList
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.GridLayout
import android.content.Intent
import android.app.PendingIntent
import android.graphics.Typeface
data class Course(
    var name: String,
    var teacher: String,
    var room: String,
    var day: Int,
    var startSection: Int,
    var endSection: Int,
    var weekList: MutableList<Int> = mutableListOf() // 必须是 MutableList
)

data class Grade(
    val semester: String, // 学期
    val name: String,     // 课程名
    val credit: String,   // 学分
    val score: String,    // 分数
    val gpa: String ,     // 绩点
    val nature: String    // 性质
)

// 1. 修改 TrainingPlan 数据类，给每个字段都加上默认值，方便拼凑
data class TrainingPlan(
    val id: String = "",         // 课程编号
    val name: String = "",       // 课程名称
    val enName: String = "",     // 英文名称
    val nature: String = "",     // 课程性质
    val system: String = "",     // 课程体系
    val credit: String = "",     // 学分
    val totalHours: String = "", // 总学时
    val lectureHours: String = "",// 讲授学时
    val labHours: String = "",   // 实验学时
    val selfStudyHours: String = "", // 自修学时
    val term: String = ""        // 执行学期
)

data class CreditCategory(
    val name: String,       // 课程性质
    val required: String,   // 应修学分
    val earned: String,     // 已修学分
    val gap: String,        // 学分差
    val progress: String    // 完成进度 (%)
)

class MainActivity : AppCompatActivity() {

    private lateinit var tickHandler: Handler
    private val tickRunnable = object : Runnable {
        override fun run() {
            // 🌟 核心：直接刷通知，不碰 UI 布局
            refreshNotificationOnly()

            // 🌟 如果你想让 ViewPager 里的课表界面也跟着刷（比如高亮切换）
            // findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.week_viewpager).adapter?.notifyDataSetChanged()

            tickHandler.postDelayed(this, 60000)
        }
    }
    // --- 【100% 照搬你提供的 OkHttpClient，保证登录不报错】 ---
    private val myClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            private val myCookieMap = HashMap<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                myCookieMap[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return myCookieMap[url.host] ?: arrayListOf()
            }
        })
        .build()

    private val mySemesterData = mutableListOf<String>()
    private val fetchedList = mutableListOf<Course>()
    private val manualList = mutableListOf<Course>()

    private var currentWeek: Int = 1
    private var semesterStartMillis: Long = 0L
    private lateinit var viewPager: ViewPager2
    private var globalScrollY = 0
    private var lastVerticalScrollY = 0 // 记录用户最后滚动到的垂直高度
    private lateinit var tvAccountDisplay: TextView
    private lateinit var loginForm: LinearLayout
    private lateinit var btnLogout: Button
    private lateinit var uInput: EditText
    private lateinit var pInput: EditText
    private var fullTrainingPlanList = mutableListOf<TrainingPlan>()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 🌟 核心修改：权限一拿到，立刻计算周次并刷新通知
                calculateCurrentWeek()
                refreshNotificationOnly()
                Toast.makeText(this, "通知权限已开启，看板已就绪", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playFadeInAnim(view: View, delay: Long) {
        view.alpha = 0f // 初始透明
        view.translationY = 100f // 初始位置向下偏移 100 像素

        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400) // 动画持续 400 毫秒
            .setStartDelay(delay) // 🌟 关键：每个卡片延迟一点点，形成“鱼贯而入”的效果
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = getSystemService(android.app.NotificationManager::class.java)

            // 🌟 统一使用 v2 标识
            val channelId = "course_monitor_v99"

            // 建议：如果之前有旧渠道，保留删除逻辑以清理冗余
            manager?.deleteNotificationChannel("course_monitor")

            val channel = android.app.NotificationChannel(
                channelId,
                "课程实时动态",
                android.app.NotificationManager.IMPORTANCE_HIGH // 🌟 必须是 HIGH 才能在锁屏“蹦”出来
            ).apply {
                description = "在锁屏显示当前课程信息"

                // 🌟 核心修改：强制设为 PUBLIC，确保锁屏时不仅仅显示“收到一条新通知”，而是显示课程名字
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC

                // 🌟 增强：允许绕过免打扰模式（可选，根据需求决定）
                setBypassDnd(true)

                // 🌟 增强：确保闪光灯和振动开启，这会提高系统对该渠道的优先级判定
                enableLights(true)
                lightColor = android.graphics.Color.BLUE
                enableVibration(true)
            }

            manager?.createNotificationChannel(channel)
        }
    }

    private fun getEncryptedPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this, "secure_config", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 🌟 如果 Vivo 硬件加密层报错，降级到普通存储，保证至少能读出账号
            Log.e("Security", "Vivo 硬件解密失败，尝试普通模式")
            getSharedPreferences("normal_config", android.content.Context.MODE_PRIVATE)
        }
    }

    // 在 MainActivity 中添加这个辅助函数
    private fun getTimeRange(section: Int): String {
        return when (section) {
            1 -> "08:10 - 08:55"
            2 -> "09:05 - 09:50"
            3 -> "10:10 - 10:55"
            4 -> "11:05 - 11:50"
            5 -> "14:20 - 15:05"
            6 -> "15:15 - 16:00"
            7 -> "16:20 - 17:05"
            8 -> "17:15 - 18:00"
            9 -> "19:30 - 20:15"
            10 -> "20:25 - 21:10"
            else -> ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tickHandler = Handler(Looper.getMainLooper())
        tickHandler.post(tickRunnable)
        checkNotificationPermission()
        // 1. 解决底部系统导航栏遮挡 UI 问题
        val mainRoot = findViewById<View>(R.id.main_layout)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 2. 绑定核心 UI 控件
        viewPager = findViewById(R.id.week_viewpager)
        tvAccountDisplay = findViewById(R.id.tv_account_display)
        uInput = findViewById<EditText>(R.id.et_student_id)
        pInput = findViewById<EditText>(R.id.et_password)
        val btnLoginAction = findViewById<Button>(R.id.btn_login)
        val creditPage = findViewById<View>(R.id.credit_page)
        val tabCreditText = findViewById<TextView>(R.id.tab_credit_text)

        // --- 找到这两个新加的容器 ---
        loginForm = findViewById<LinearLayout>(R.id.layout_login_form)
        btnLogout = findViewById<Button>(R.id.btn_logout)

        // 💡 提取出一个专门刷新账号页 UI 的方法

        // 初始化时调一次
        refreshAccountUI()

        // 💡 退出登录按钮的逻辑
        btnLogout.setOnClickListener {
            // 1. 清除本地保存的账号密码和课表缓存
            getEncryptedPrefs().edit().clear().apply()
            myClient.cookieJar.let { (it as? HashMap<*, *>)?.clear() }

            // 2. 清空内存里的数据
            fetchedList.clear()
            manualList.clear()
            mySemesterData.clear()
            viewPager.adapter?.notifyDataSetChanged() // 刷新课表（变空白）
            findViewById<LinearLayout>(R.id.grade_container).removeAllViews() // 清空成绩
            findViewById<TextView>(R.id.tv_exam_content).text = "暂无考试安排信息" // 清空考试

            // 3. 把输入框里的字清空
            uInput.setText("")
            pInput.setText("")

            // 4. 刷新界面为未登录状态
            refreshAccountUI()
            Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
        }

        // 页面容器绑定
        val kbPage = findViewById<View>(R.id.week_viewpager)
        val examPage = findViewById<View>(R.id.exam_page)
        val gradePage = findViewById<View>(R.id.grade_page)
        val accountPage = findViewById<View>(R.id.account_page)

        // 标题栏绑定
        val tvTopWeek = findViewById<TextView>(R.id.tv_top_week)
        val tvTitle = findViewById<TextView>(R.id.tv_title)
        val btnPlus = findViewById<ImageButton>(R.id.btn_plus_menu)
        val tvAccountDisplay = findViewById<TextView>(R.id.tv_account_display)

        // 底部 Tab 文字绑定
        val tabKbText = findViewById<TextView>(R.id.tab_kb_text)
        val tabExamText = findViewById<TextView>(R.id.tab_exam_text)
        val tabGradeText = findViewById<TextView>(R.id.tab_grade_text)
        val tabAccountText = findViewById<TextView>(R.id.tab_account_text)

        // 3. 读取本地持久化配置
        val prefs = getEncryptedPrefs()
        semesterStartMillis = prefs.getLong("start_date", 0L)
        val savedId = prefs.getString("student_id", "")
        val savedPwd = prefs.getString("password", "")

        // 🌟 新增触发逻辑：只要本地存了账号密码，启动时就去偷偷唤醒 Session！
        if (!savedId.isNullOrEmpty() && !savedPwd.isNullOrEmpty()) {
            silentLogin(savedId, savedPwd)
        }
        calculateCurrentWeek()

        // 4. 初始化 ViewPager2 (左右滑动课表)
        viewPager.adapter = WeekAdapter()
        loadCoursesFromLocal() // 尝试加载离线保存的课表

        if (currentWeek in 1..25) {
            viewPager.setCurrentItem(currentWeek - 1, false)
        }
        // 🌟 核心修复：启动瞬间强制同步左上角周数文字与颜色
        tvTopWeek.text = "第 ${currentWeek} 周(本周)"
        tvTopWeek.setTextColor(Color.parseColor("#2196F3")) // 强制变蓝色，保持“本周”的高亮感

        // 🌟 核心修改后的代码
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val weekNum = position + 1
                tvTopWeek.text = if (weekNum == currentWeek) "第 $weekNum 周(本周)" else "第 $weekNum 周"
                tvTopWeek.setTextColor(if (weekNum == currentWeek) Color.parseColor("#2196F3") else Color.GRAY)

                // 🌟 核心修复：新页面加载后，强制同步之前的滚动高度
                viewPager.post {
                    val recyclerView = viewPager.getChildAt(0) as androidx.recyclerview.widget.RecyclerView
                    val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)

                    // 这里类型改成了 android.widget.ScrollView，ID 改成了 week_scroll_view
                    val scrollView = viewHolder?.itemView?.findViewById<android.widget.ScrollView>(R.id.week_scroll_view)

                    scrollView?.scrollTo(0, lastVerticalScrollY)
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                // 当用户手指按住屏幕开始“拖拽”的一瞬间
                if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING) {
                    val recyclerView = viewPager.getChildAt(0) as androidx.recyclerview.widget.RecyclerView
                    val viewHolder = recyclerView.findViewHolderForAdapterPosition(viewPager.currentItem)
                    val scrollView = viewHolder?.itemView?.findViewById<android.widget.ScrollView>(R.id.week_scroll_view)

                    // 🌟 核心记录：在翻走之前，先记住当前周滚到了哪里
                    lastVerticalScrollY = scrollView?.scrollY ?: 0

                    if (selDay != -1) {
                        selDay = -1
                        viewPager.adapter?.notifyDataSetChanged()
                    }
                }
            }
        })

        // 6. 统一 Tab 切换逻辑 (彻底解决点击穿透)
        fun switchTab(index: Int) {
            // 先全部隐藏
            kbPage.visibility = View.GONE
            examPage.visibility = View.GONE
            gradePage.visibility = View.GONE
            creditPage.visibility = View.GONE // 🌟 新增
            accountPage.visibility = View.GONE

            when (index) {
                0 -> {
                    kbPage.visibility = View.VISIBLE
                    tvTopWeek.visibility = View.VISIBLE
                    btnPlus.visibility = View.VISIBLE
                    tvTitle.text = "我的课表"
                }
                1 -> {
                    examPage.visibility = View.VISIBLE
                    tvTopWeek.visibility = View.GONE; btnPlus.visibility = View.GONE
                    tvTitle.text = "考试安排"
                    fetchExams()
                }
                2 -> {
                    gradePage.visibility = View.VISIBLE
                    tvTopWeek.visibility = View.GONE; btnPlus.visibility = View.GONE
                    tvTitle.text = "成绩查询"
                }
                3 -> { // 🌟 新增的学分页逻辑
                    creditPage.visibility = View.VISIBLE
                    tvTopWeek.visibility = View.GONE; btnPlus.visibility = View.GONE
                    tvTitle.text = "学分与计划"
                }
                4 -> { // 🌟 原来的账号页变成了 4
                    accountPage.visibility = View.VISIBLE
                    tvTopWeek.visibility = View.GONE; btnPlus.visibility = View.GONE
                    tvTitle.text = "个人中心"
                    refreshAccountUI()
                }
            }

            // 更新底部 5 个 Tab 颜色
            val texts = arrayOf(tabKbText, tabExamText, tabGradeText, tabCreditText, tabAccountText)
            texts.forEachIndexed { i, tv ->
                tv.setTextColor(if (i == index) Color.parseColor("#2196F3") else Color.GRAY)
                tv.setTypeface(null, if (i == index) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }

        // 7. 绑定底部 4 个 Tab 按钮
        findViewById<View>(R.id.tab_kb_btn).setOnClickListener { switchTab(0) }
        findViewById<View>(R.id.tab_exam_btn).setOnClickListener { switchTab(1) }
        findViewById<View>(R.id.tab_grade_btn).setOnClickListener { switchTab(2) }
        findViewById<View>(R.id.tab_credit_btn).setOnClickListener { switchTab(3) } // 学分是 3
        findViewById<View>(R.id.tab_account_btn).setOnClickListener { switchTab(4) } // 设置/登录是 4

        // 8. 绑定成绩页面顶部的三个子功能按钮 (重要修复：按钮不再失效)
        findViewById<Button>(R.id.btn_all_grades).setOnClickListener {
            fetchGradeData("https://jiaowu.sicau.edu.cn/xuesheng/chengji/chengji/sear_ch_all.asp", "全部成绩")
        }
        findViewById<Button>(R.id.btn_ranking).setOnClickListener {
            findViewById<LinearLayout>(R.id.grade_container).removeAllViews()
            updateStatus("正在查询排名统计...")
            // 排名统计的专属 URL
            fetchRankingData("https://jiaowu.sicau.edu.cn/xuesheng/chengji/chengji/zytongbf.asp")
        }
        val etPlanSearch = findViewById<EditText>(R.id.et_plan_search)

        // 1. 给搜索框加上“打字实时监听”
        etPlanSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: android.text.Editable?) {
                val keyword = s?.toString()?.trim() ?: ""
                if (keyword.isEmpty()) {
                    // 如果搜索框空了，就把仓库里所有的课都画出来
                    renderPlanCards(fullTrainingPlanList)
                } else {
                    // 🌟 核心过滤逻辑：从仓库里挑出名字包含关键字的课
                    val filteredList = fullTrainingPlanList.filter {
                        it.name.contains(keyword, ignoreCase = true)
                    }
                    renderPlanCards(filteredList) // 只画过滤出来的课
                }
            }
        })

        // 2. 学分按钮点击事件
        findViewById<Button>(R.id.btn_my_credits).setOnClickListener {
            // 隐藏搜索框（学分统计不需要搜索）
            findViewById<EditText>(R.id.et_plan_search).visibility = View.GONE
            // 清空旧文字提示
            findViewById<TextView>(R.id.tv_credit_content).text = ""
            // 调用新的学分汇总函数
            fetchCreditSummaryData("https://jiaowu.sicau.edu.cn/xuesheng/chengji/xdjd/xuefen_2023.asp?title_id1=1")
        }

        // 3. 培养方案按钮点击事件
        findViewById<Button>(R.id.btn_training_plan).setOnClickListener {
            etPlanSearch.visibility = View.VISIBLE // 🌟 把搜索框显示出来！
            etPlanSearch.setText("") // 每次切过来清空上次搜的内容

            findViewById<TextView>(R.id.tv_credit_content).text = ""
            // 拉取数据
            fetchTrainingPlanData("https://jiaowu.sicau.edu.cn/xuesheng/jihua/jihua/jjihua.asp?title_id1=1")
        }
        // 9. 登录及功能按钮逻辑
        btnLoginAction.setOnClickListener {
            val u = uInput.text.toString()
            val p = pInput.text.toString()
            if (u.isNotEmpty()) {
                updateStatus("正在初始化登录...")
                step1FetchTokens(u, p)
            }
        }
        val tvForgotPwd = findViewById<TextView>(R.id.tv_forgot_password)
        tvForgotPwd.setOnClickListener {
            showForgotPasswordDialog()
        }

        btnPlus.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("查询新课表")
            popup.menu.add("设置开学日期")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "查询新课表" -> showSemesterQueryDialog()
                    "设置开学日期" -> showDatePicker()
                }
                true
            }
            popup.show()
        }

        // 在 onCreate 结尾
        Handler(Looper.getMainLooper()).postDelayed({
            if (fetchedList.isNotEmpty()) {
                refreshNotificationOnly()
            }
        }, 1000) // 延迟1秒，等本地数据 load 完成
        // 强制发送测试通知，排除逻辑错误
    }

    // 💡 把它作为独立的方法放在类里面
    private fun refreshAccountUI() {
        val currentId = getEncryptedPrefs().getString("student_id", "")
        if (currentId.isNullOrEmpty()) {
            tvAccountDisplay.text = "状态: 未登录"
            loginForm.visibility = View.VISIBLE
            btnLogout.visibility = View.GONE
        } else {
            tvAccountDisplay.text = "当前学号: $currentId"
            loginForm.visibility = View.GONE
            btnLogout.visibility = View.VISIBLE
        }
    }

    private fun fetchExams() {
        val examUrl = "https://jiaowu.sicau.edu.cn/xuesheng/kao/kao/xuesheng.asp?title_id1=01"
        val request = Request.Builder()
            .url(examUrl)
            .addHeader("Referer", "https://jiaowu.sicau.edu.cn/jiaoshi/bangong/index1.asp")
            .build()

        updateStatus("正在抓取考试安排...")

        myClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { updateStatus("抓取考试失败") }
            override fun onResponse(call: Call, response: Response) {
                val html = decodeGb2312(response)
                val doc = Jsoup.parse(html)
                val table = doc.select("table").lastOrNull() ?: return
                val rows = table.select("tr")
                val sb = StringBuilder()

                for (i in 1 until rows.size) {
                    val tds = rows[i].select("td")
                    if (tds.size >= 5) {
                        sb.append("📖 课程：${tds[1].text()}\n")
                        sb.append("⏰ 时间：${tds[3].text()}\n")
                        sb.append("📍 地点：${tds[4].text()}  座号：${tds[5].text()}\n")
                        sb.append("----------------------------\n")
                    }
                }

                runOnUiThread {
                    findViewById<TextView>(R.id.tv_exam_content).text =
                        if (sb.isEmpty()) "目前没有查询到正考安排。" else sb.toString()
                    updateStatus("考试安排更新成功")
                }
            }
        })
    }

    private fun fetchGradeData(targetUrl: String, label: String) {
        updateStatus("正在拉取 $label 数据...")
        val container = findViewById<LinearLayout>(R.id.grade_container)
        container.removeAllViews() // 清空旧数据

        val request = Request.Builder()
            .url(targetUrl)
            // 💡 关键：Referer 必须设置，否则教务处可能拦截
            .addHeader("Referer", "https://jiaowu.sicau.edu.cn/xuesheng/chengji/chengji/chengji.asp")
            .build()

        myClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                updateStatus("网络连接失败，请检查VPN")
            }

            override fun onResponse(call: Call, response: Response) {
                val html = decodeGb2312(response)
                val doc = Jsoup.parse(html)
                val gradeList = mutableListOf<Grade>()

                // 判定当前是排名页还是普通成绩页
                val isRankingPage = targetUrl.contains("zytongbf.asp")

                // 扫描页面所有行
                val allRows = doc.select("tr")

                for (row in allRows) {
                    val tds = row.select("td")

                    if (isRankingPage) {
                        // --- 1. 必修加权排名页解析 (约11列) ---
                        // 序(0), 校区(1), 系别(2), 专业(3), 年级(4), 学号(5), 姓名(6), 班级(7), 成绩(8), 排名(9)
                        if (tds.size >= 10) {
                            val rank = tds[9].text().trim()
                            val weightedScore = tds[8].text().trim()

                            // 只要排名是数字，就认为是我们需要的那一行数据
                            if (rank.any { it.isDigit() }) {
                                gradeList.add(Grade(
                                    semester = "专业排名: $rank",
                                    name = "有效必修加权成绩",
                                    credit = "专业: ${tds[3].text().trim()} | 班级: ${tds[7].text().trim()}",
                                    score = weightedScore,
                                    gpa = "",
                                    nature = "排名" // 排名页的性质显示为“排名”
                                ))
                            }
                        }
                    } else {
                        // --- 2. 全部成绩页解析 (约18列) ---
                        // 下标对应：4课程, 7学分, 9学期, 10成绩, 11绩点, 14开课课程性质
                        if (tds.size >= 15) {
                            val semesterStr = tds[9].text().trim()
                            // 过滤条件：学期列包含“20”年份且课程名不为空
                            if (semesterStr.contains("20") && tds[4].text().isNotBlank()) {
                                gradeList.add(Grade(
                                    semester = semesterStr,
                                    name = tds[4].text().trim(),
                                    credit = tds[7].text().trim(),
                                    score = tds[10].text().trim(),
                                    gpa = tds[11].text().trim(),
                                    nature = tds[14].text().trim() // 💡 抓取开课课程性质
                                ))
                            }
                        }
                    }
                }

                // 只有成绩页需要按照学期（时间）进行倒序排序
                if (!isRankingPage) {
                    gradeList.sortByDescending { it.semester }
                }

                runOnUiThread {
                    if (gradeList.isEmpty()) {
                        updateStatus("暂无数据。请确认是否已评教或Session过期。")
                        // 调试：如果出不来，可以在此处 Log.d("DEBUG", html) 查看源码
                    } else {
                        updateStatus("$label 加载完成 (共 ${gradeList.size} 条)")
                        // 调用渲染函数画出带性质标签的卡片
                        renderGradeCards(gradeList)
                    }
                }
            }
        })
    }


    private fun showEmptyState(container: LinearLayout, message: String) {
        container.removeAllViews()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 150, 0, 0)
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_info_details)
            alpha = 0.2f
            layoutParams = LinearLayout.LayoutParams(180, 180)
        }

        val tv = TextView(this).apply {
            text = message
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 30, 0, 0)
        }

        layout.addView(icon)
        layout.addView(tv)
        container.addView(layout)
    }
    private fun renderGradeCards(grades: List<Grade>) {
        val container = findViewById<LinearLayout>(R.id.grade_container) ?: return
        container.removeAllViews()

        // 🌟 核心修改 1：改用 forEachIndexed 拿到 index
        grades.forEachIndexed { index, g ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 35, 40, 35)
                val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                lp.setMargins(0, 0, 0, 30)
                layoutParams = lp
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 30f
                }
                elevation = 4f
            }

            // --- 内部 UI 逻辑保持你原来的不动 ---
            val topRow = RelativeLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            val tvScore = TextView(this).apply {
                id = View.generateViewId()
                text = g.score
                textSize = 20f
                setTextColor(Color.parseColor("#00897B"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                val p = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                p.addRule(RelativeLayout.ALIGN_PARENT_END)
                p.addRule(RelativeLayout.CENTER_VERTICAL)
                layoutParams = p
            }
            val leftContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                p.addRule(RelativeLayout.ALIGN_PARENT_START)
                p.addRule(RelativeLayout.START_OF, tvScore.id)
                p.setMarginEnd(20)
                layoutParams = p
            }
            val tvName = TextView(this).apply {
                text = g.name; textSize = 15f; setTextColor(Color.parseColor("#333333"))
                setTypeface(null, android.graphics.Typeface.BOLD); setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val tvGpa = TextView(this).apply {
                text = " [绩点 ${g.gpa}]"; textSize = 13f; setTextColor(Color.parseColor("#2196F3"))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }
            leftContainer.addView(tvName); leftContainer.addView(tvGpa)
            topRow.addView(tvScore); topRow.addView(leftContainer)
            val tvDetail = TextView(this).apply {
                this.text = "学分: ${g.credit}  |  ${g.nature}"
                this.setTextSize(12f); this.setTextColor(Color.parseColor("#999999"))
                setPadding(0, 10, 0, 0)
            }
            card.addView(topRow); card.addView(tvDetail)
            // ------------------------------------

            container.addView(card)

            // 🌟 核心修改 2：给每个卡片加上“丝滑入场”动画
            card.alpha = 0f         // 初始透明
            card.translationY = 60f // 初始向下位移一点

            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(400)
                // 🌟 每个卡片比前一个晚 50 毫秒，形成阶梯流出的效果
                .setStartDelay((index * 50).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }


    private fun renderRankingSummary(dataText: String) {
        val container = findViewById<LinearLayout>(R.id.grade_container) ?: return
        container.removeAllViews()

        // 🌟 清理所有特殊空格，按空格切开
        val cleanText = dataText.replace("&nbsp;", "").replace("\u00A0", " ")
        val parts = cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }

        // 根据你提供的顺序：序(0) 校区(1) 系别(2) 专业(3) 班级(4) 学号(5) 年级(6) 姓名(7) 加权分(8) 排名(9)
        // 🚨 注意：如果切出来的 parts 第一项是“1”，那么索引是对的；如果第一项是“校区”，则需要索引+1
        if (parts.size < 10) return

        val major = parts[3]
        val className = parts[4]
        val score = parts[8]
        val rank = parts[9]

        val card = LinearLayout(this).apply {
            setOrientation(LinearLayout.VERTICAL)
            setPadding(50, 40, 50, 40)
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 30)
            setLayoutParams(lp)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setCornerRadius(30f)
            }
            setElevation(4f)
        }

        // 1. 标题：专业排名（红色）
        val tvRank = TextView(this).apply {
            setText("专业排名：$rank") // 🌟 排名做标题
            setTextSize(22f)
            setTextColor(Color.parseColor("#D32F2F")) // 醒目的红色
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }

        // 2. 详情内容（无绩点，浅灰色）
        val tvInfo = TextView(this).apply {
            val details = """
                专业：$major
                班级：$className
                加权平均分：$score
            """.trimIndent()

            setText(details)
            setTextSize(15f)
            setLineSpacing(15f, 1.0f)
            setTextColor(Color.parseColor("#607D8B")) // 浅灰蓝，显高级
        }

        card.addView(tvRank)
        card.addView(tvInfo)
        container.addView(card)
    }


    private fun fetchRankingData(targetUrl: String) {
        val request = Request.Builder()
            .url(targetUrl)
            .addHeader("Referer", "https://jiaowu.sicau.edu.cn/xuesheng/chengji/chengji/chengji.asp")
            .build()

        myClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { updateStatus("查询失败") }
            }

            override fun onResponse(call: Call, response: Response) {
                val html = decodeGb2312(response)
                val doc = Jsoup.parse(html)

                // 1. 获取当前登录的学号
                val myId = getEncryptedPrefs().getString("student_id", "") ?: ""

                // 2. 🌟 核心改进：遍历所有行，只抓取包含学号的那一行数据
                val rows = doc.select("tr")
                var dataRowText = ""
                for (row in rows) {
                    val text = row.text()
                    // 只有同时包含学号和“雅安”（或成都/都江堰）的行才是我们要的成绩行
                    if (text.contains(myId) && (text.contains("雅安") || text.contains("成都") || text.contains("都江堰"))) {
                        dataRowText = text
                        break
                    }
                }

                runOnUiThread {
                    if (dataRowText.isNotEmpty()) {
                        renderRankingSummary(dataRowText)
                    } else {
                        updateStatus("未找到您的排名数据")
                    }
                }
            }
        })
    }

    private fun fetchCreditSummaryData(targetUrl: String) {
        val container = findViewById<LinearLayout>(R.id.plan_container)
        container.removeAllViews()
        updateStatus("正在计算学业进度...")

        val request = Request.Builder()
            .url(targetUrl)
            .addHeader("Referer", "https://jiaowu.sicau.edu.cn/jiaoshi/bangong/index1.asp")
            .build()

        myClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                updateStatus("无法连接教务处")
            }

            override fun onResponse(call: Call, response: Response) {
                val html = decodeGb2312(response)

                // 1. 强力清理：干掉所有形式的 sp (&nbsp; \u00A0) 和杂乱空白
                val cleanHtml = html.replace("&nbsp;", "")
                    .replace("\u00A0", "")
                    .replace("\u2002", "") // 半角空格
                    .replace("\u3000", "") // 全角空格

                val doc = Jsoup.parse(cleanHtml)
                val summaryList = mutableListOf<CreditCategory>()
                val rows = doc.select("tr")

                for (row in rows) {
                    // 获取这一行所有非空的格子内容
                    val cells = row.select("td").map { it.text().trim() }.filter { it.isNotEmpty() }

                    // 🌟 核心识别逻辑：
                    // 只有当这一行包含类似 "58.88%" 这样的百分比，或者是“合计”行时，才是我们要的数据
                    val hasProgress = cells.any { it.contains("%") }
                    val isTotal = cells.any { it.contains("合计") }

                    if (hasProgress || isTotal) {
                        // 找到第一个看起来像学分数字（数字或带小数）的格子索引
                        val firstNumIdx = cells.indexOfFirst { it.matches(Regex("^-?[0-9.]+$")) }

                        if (firstNumIdx != -1) {
                            // 1. 名字：数字之前的所有格子拼起来
                            val name = cells.subList(0, firstNumIdx).joinToString("-")

                            // 2. 数据提取：从第一个数字开始往后拿
                            val required = cells.getOrNull(firstNumIdx) ?: "0"
                            val earned = cells.getOrNull(firstNumIdx + 1) ?: "0"
                            val gap = cells.getOrNull(firstNumIdx + 2) ?: "0"

                            // 3. 进度：拿最后一个带百分号的格子
                            val progressStr = cells.lastOrNull { it.contains("%") } ?: "0"

                            summaryList.add(CreditCategory(
                                name = name.replace(" ", ""),
                                required = required,
                                earned = earned,
                                gap = gap,
                                progress = progressStr.replace("%", "").trim()
                            ))
                        }
                    }
                }

                runOnUiThread {
                    if (summaryList.isEmpty()) {
                        updateStatus("暂无学分统计数据")
                    } else {
                        updateStatus("学分统计加载完成 (共 ${summaryList.size} 项)")
                        renderCreditDashboard(summaryList)
                    }
                }
            }
        })
    }

    private fun renderCreditDashboard(categories: List<CreditCategory>) {
        val container = findViewById<LinearLayout>(R.id.plan_container) ?: return
        container.removeAllViews()

        findViewById<TextView>(R.id.tv_credit_content)?.let { it.visibility = View.GONE }

        // 🌟 核心修改 1：改用 forEachIndexed 拿到 index 来做阶梯动画
        categories.forEachIndexed { index, cat ->
            val card = LinearLayout(this).apply {
                setOrientation(LinearLayout.VERTICAL)
                setPadding(48, 44, 48, 44)
                val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                lp.setMargins(0, 0, 0, 36)
                setLayoutParams(lp)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setCornerRadius(48f)
                }
                setElevation(4f)
            }

            // --- 内部 UI 逻辑完全保留你原来的（RelativeLayout、ProgressBar 等） ---
            val titleRow = RelativeLayout(this).apply {
                setLayoutParams(LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val tvPercent = TextView(this).apply {
                id = View.generateViewId()
                setText("${cat.progress}%")
                setTextSize(16f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                val p = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                p.addRule(RelativeLayout.ALIGN_PARENT_END)
                p.addRule(RelativeLayout.CENTER_VERTICAL)
                setLayoutParams(p)
                val progressVal = cat.progress.toDoubleOrNull() ?: 0.0
                setTextColor(Color.parseColor(when {
                    progressVal >= 90 -> "#4CAF50"
                    progressVal >= 60 -> "#1A237E"
                    else -> "#FFA726"
                }))
            }
            val tvName = TextView(this).apply {
                setText(cat.name); setTextSize(15f); setTextColor(Color.parseColor("#1A237E"))
                setTypeface(null, android.graphics.Typeface.BOLD); setSingleLine(true)
                setEllipsize(android.text.TextUtils.TruncateAt.END)
                val p = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                p.addRule(RelativeLayout.ALIGN_PARENT_START)
                p.addRule(RelativeLayout.START_OF, tvPercent.id)
                p.setMarginEnd(20)
                setLayoutParams(p)
            }
            titleRow.addView(tvPercent); titleRow.addView(tvName)

            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                setLayoutParams(LinearLayout.LayoutParams(MATCH_PARENT, 16).apply { setMargins(0, 24, 0, 24) })
                setMax(100)
                setProgress(cat.progress.toDoubleOrNull()?.toInt() ?: 0)
                setProgressTintList(ColorStateList.valueOf(tvPercent.currentTextColor))
                setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#ECEFF1")))
            }

            val tvData = TextView(this).apply {
                val originalGap = cat.gap.toDoubleOrNull() ?: 0.0
                val flippedGap = originalGap * -1
                val gapStatusText = when {
                    flippedGap < 0 -> "已超修 ${-flippedGap} 分"
                    flippedGap > 0 -> "还差 $flippedGap 分"
                    else -> "已修满"
                }
                setText("已修 ${cat.earned} / 应修 ${cat.required} ($gapStatusText)")
                setTextSize(12f)
                if (flippedGap <= 0) setTextColor(Color.parseColor("#4CAF50")) else setTextColor(Color.parseColor("#90A4AE"))
            }

            card.addView(titleRow)
            card.addView(progressBar)
            card.addView(tvData)
            // -------------------------------------------------------------------

            container.addView(card)

            // 🌟 核心修改 2：注入灵魂，设置出场动画
            card.alpha = 0f          // 初始透明
            card.translationY = 50f  // 初始向下偏移

            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)    // 动画时长
                // 🌟 阶梯效果：第一个立即出，第二个晚80ms，第三个晚160ms...
                .setStartDelay((index * 80).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun showSemesterQueryDialog() {
        if (mySemesterData.isEmpty()) {
            Toast.makeText(this, "请先在账号页登录并等待学期加载", Toast.LENGTH_SHORT).show()
            return
        }

        // 动态在代码里创建一个 Spinner
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mySemesterData)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setPadding(60, 40, 60, 40) // 增加点间距好看些

        AlertDialog.Builder(this)
            .setTitle("选择查询学期")
            .setView(spinner) // 把这个下拉框塞进弹窗里
            .setPositiveButton("开始查询") { _, _ ->
                val selectedSem = spinner.selectedItem?.toString()
                if (selectedSem != null) {
                    updateStatus("正在查询 $selectedSem 的课表...")
                    step5SetSemContext(selectedSem) // 调用你的查询函数
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }


    // 🌟 新增：静默登录功能（只在后台拿 Cookie，不弹窗，不跳页）
    private fun silentLogin(u: String, p: String) {
        val url = "https://jiaowu.sicau.edu.cn/web/web/web/index.asp"
        myClient.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SilentLogin", "静默登录失败：网络故障")
            }

            override fun onResponse(call: Call, response: Response) {
                val html = decodeGb2312(response)
                val myDoc = Jsoup.parse(html)
                val mySignValue = myDoc.select("input[name=sign]").attr("value")
                val myHourValue = myDoc.select("input[name=hour_key]").attr("value")

                if (mySignValue.isNotEmpty()) {
                    val loginUrl = "https://jiaowu.sicau.edu.cn/jiaoshi/bangong/check.asp"
                    val body = FormBody.Builder()
                        .add("user", u)
                        .add("pwd", p)
                        .add("lb", "S")
                        .add("sign", mySignValue)
                        .add("hour_key", myHourValue)
                        .add("submit", "")
                        .build()

                    val req = Request.Builder()
                        .url(loginUrl)
                        .post(body)
                        .addHeader("Referer", url)
                        .build()

                    myClient.newCall(req).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            Log.e("SilentLogin", "POST请求失败")
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val resHtml = decodeGb2312(response)

                            if (resHtml.contains("欢迎") || resHtml.contains("index1.asp")) {
                                Log.d("SilentLogin", "✅ 静默登录成功，Cookie已刷新")
                                // 🌟 登录成功，偷偷拉取学期数据
                                step4FetchSemesters(true)
                            } else {
                                // 🌟 核心改进：静默登录失败时的处理
                                runOnUiThread {
                                    when {
                                        // 检查是否触发了短信验证
                                        resHtml.contains("短信") || resHtml.contains("动态码") -> {
                                            Toast.makeText(this@MainActivity, "检测到需要短信验证码，请手动登录", Toast.LENGTH_LONG).show()
                                            // 💡 提示：此时不要清空数据，让用户去账号页手动点登录
                                        }

                                        // 检查是否被锁定
                                        resHtml.contains("锁定") || resHtml.contains("超过5次") -> {
                                            Toast.makeText(this@MainActivity, "⚠️ 账号已被锁定，请明天再试", Toast.LENGTH_LONG).show()
                                        }

                                        // 其他情况（如密码过期等）
                                        else -> {
                                            Log.w("SilentLogin", "自动唤醒Session失败，可能密码已更改")
                                        }
                                    }

                                    // ❌ 严禁在此处调用 btnLogout.performClick()
                                    // 这样即便失败了，用户原来的学号密码依然保存在手机里，下次还能用
                                }
                            }
                        }
                    })
                }
            }
        })
    }
    // --- 【100% 照搬你提供的步骤1-5】 ---
    private fun step1FetchTokens(u: String, p: String) {
        val url = "https://jiaowu.sicau.edu.cn/web/web/web/index.asp"
        myClient.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { updateStatus("网络故障") }
            override fun onResponse(call: Call, response: Response) {
                val html = decodeGb2312(response)
                val myDoc = Jsoup.parse(html)
                val mySignValue = myDoc.select("input[name=sign]").attr("value")
                val myHourValue = myDoc.select("input[name=hour_key]").attr("value")
                if (mySignValue.isNotEmpty()) {
                    updateStatus("正在登录...")
                    step2DoLogin(u, p, mySignValue, myHourValue)
                }
            }
        })
    }

    private fun step2DoLogin(u: String, p: String, s: String, h: String) {
        val loginUrl = "https://jiaowu.sicau.edu.cn/jiaoshi/bangong/check.asp"
        val body = FormBody.Builder()
            .add("user", u)
            .add("pwd", p)
            .add("lb", "S")
            .add("sign", s)
            .add("hour_key", h)
            .add("submit", "")
            .build()

        val req = Request.Builder()
            .url(loginUrl)
            .post(body)
            .addHeader("Referer", "https://jiaowu.sicau.edu.cn/web/web/web/index.asp")
            .build()

        myClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                updateStatus("网络连接异常")
            }

            override fun onResponse(call: Call, response: Response) {
                val html = decodeGb2312(response)

                // 🌟 1. 登录成功的情况
                if (html.contains("欢迎") || html.contains("index1.asp")) {
                    updateStatus("登录成功！")
                    runOnUiThread { refreshAccountUI() }

                    // 💡 核心逻辑：既然你分析出“收到短信代表原密码是对的”，
                    if (p.length != 6) {
                        // 只有长密码才更新密码字段
                        getEncryptedPrefs().edit()
                            .putString("student_id", u)
                            .putString("password", p)
                            .apply()
                        Log.d("AppStatus", "已更新持久化密码")
                    } else {
                        // 如果是 6 位，我们认为它是临时验证码
                        // 我们只更新学号，确保 UI 上的学号显示是对的
                        getEncryptedPrefs().edit()
                            .putString("student_id", u)
                            .apply()
                        Log.d("AppStatus", "验证码登录成功，跳过密码覆盖")
                    }

                    runOnUiThread {
                        findViewById<TextView>(R.id.tv_account_display).text = "学号: $u"
                        findViewById<View>(R.id.tab_kb_btn).performClick()
                    }

                    try {
                        myClient.newCall(Request.Builder().url("https://jiaowu.sicau.edu.cn/jiaoshi/bangong/welcome1.asp").build()).execute()
                    } catch (e: Exception) {}
                    step4FetchSemesters()

                } else {
                    // 🌟 2. 登录未成功，根据你的发现精准提示
                    runOnUiThread {
                        when {
                            // 识别：原密码正确，但系统要求输入短信验证码
                            html.contains("短信验证码") || html.contains("尾号") -> {
                                // 💡 提示词修改：认可用户的密码是对的，引导输入短信码
                                Toast.makeText(this@MainActivity, "📩 身份验证：请在密码框输入收到的 6 位短信码再次登录", Toast.LENGTH_LONG).show()
                                updateStatus("等待输入短信动态码")
                            }

                            // 识别：彻底锁死
                            html.contains("超过5次") || html.contains("锁定") -> {
                                Toast.makeText(this@MainActivity, "🚫 错误次数过多，账号已锁定，请明天再试", Toast.LENGTH_LONG).show()
                                updateStatus("账号锁定中")
                            }

                            else -> {
                                Toast.makeText(this@MainActivity, "登录失败，请检查学号密码或网络", Toast.LENGTH_SHORT).show()
                                updateStatus("用户名或密码错误")
                            }
                        }
                    }
                }
            }
        })
    }

    // 💡 新增一个 isSilent 参数，默认是 false（非静默）
    private fun step4FetchSemesters(isSilent: Boolean = false) {
        val bxqUrl = "https://jiaowu.sicau.edu.cn/xuesheng/gongxuan/gongxuan/bxq.asp"
        val req = Request.Builder()
            .url(bxqUrl)
            .addHeader("Referer", "https://jiaowu.sicau.edu.cn/jiaoshi/bangong/index1.asp")
            .build()

        myClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val html = decodeGb2312(response)
                val myDoc = Jsoup.parse(html)
                val semesterLinks = myDoc.select("a[href*=xueqi=]")

                mySemesterData.clear()
                for (linkElement in semesterLinks) {
                    mySemesterData.add(linkElement.text().trim())
                }

                runOnUiThread {
                    // 🌟 核心修改：如果是静默模式，就只打 Log；如果不是，才弹窗提示！
                    if (isSilent) {
                        Log.d("AppStatus", "后台静默抓取学期完成")
                    } else {
                        updateStatus("✅ 学期抓取成功，请点击右上角[+]查询课表")
                    }
                }
            }
        })
    }

    private fun step5SetSemContext(sem: String) {
        val setUrl = "https://jiaowu.sicau.edu.cn/xuesheng/gongxuan/gongxuan/xszhinan.asp?title_id1=9&xueqi=$sem"
        val kbUrl = "https://jiaowu.sicau.edu.cn/xuesheng/gongxuan/gongxuan/kbbanji.asp?title_id1=4"
        myClient.newCall(Request.Builder().url(setUrl).build()).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val kbReq = Request.Builder().url(kbUrl).addHeader("Referer", setUrl).build()
                myClient.newCall(kbReq).enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        runOnUiThread { parseFinalKb(decodeGb2312(response)) }
                    }
                    override fun onFailure(call: Call, e: IOException) {}
                })
            }
            override fun onFailure(call: Call, e: IOException) {}
        })
    }



    private fun showCourseEditDialog(c: Course?, d: Int = 0, s: Int = 0, e: Int = 0) {
        val isEdit = c != null
        val target = c ?: Course("", "", "", d, s, e, (1..25).toMutableList())

        // 🌟 1. 获取主题色：编辑时跟随课程颜色，添加时默认蓝色
        val themeColor = if (isEdit) {
            val hue = (target.name.hashCode().toLong().let { java.util.Random(it).nextInt(360) }).toFloat()
            androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, 0.5f, 0.45f))
        } else {
            Color.parseColor("#2196F3")
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.parseColor("#F8F9FA")) // 浅灰色底，突出白色卡片
        }

        // 🌟 2. 顶部时间指示标签
        val tvTimeTag = TextView(this).apply {
            text = "⏰ 周${target.day} 第${target.startSection}-${target.endSection}节"
            textSize = 13f
            setTextColor(themeColor)
            setPadding(20, 10, 20, 10)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(themeColor.adjustAlpha(0.1f))
                cornerRadius = 100f
            }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                setMargins(0, 0, 0, 30)
            }
        }
        root.addView(tvTimeTag)

        // 🌟 3. 基础信息卡片（白色圆角）
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 30f
            }
            elevation = 4f
        }

        fun createStyledEdit(hintText: String, initVal: String): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(initVal)
                background = null // 去掉难看的下划线
                setPadding(10, 30, 10, 30)
                textSize = 15f
            }
        }

        val nE = createStyledEdit("请输入课程名", target.name)
        val rE = createStyledEdit("请输入教室", target.room)
        val tE = createStyledEdit("请输入老师名字", target.teacher)

        infoCard.addView(nE)
        // 加两条细细的分隔线
        infoCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 2); setBackgroundColor(Color.parseColor("#EEEEEE")) })
        infoCard.addView(rE)
        infoCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 2); setBackgroundColor(Color.parseColor("#EEEEEE")) })
        infoCard.addView(tE)
        root.addView(infoCard)

        // 🌟 4. 周次选择标题与快捷按钮
        val weekTitleRow = RelativeLayout(this).apply {
            setPadding(10, 40, 10, 20)
        }
        val tvWeekTitle = TextView(this).apply {
            text = "选择上课周次"
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }
        weekTitleRow.addView(tvWeekTitle)
        root.addView(weekTitleRow)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 20)
        }
        fun createQuickBtn(label: String) = Button(this, null, android.R.attr.buttonStyleSmall).apply {
            text = label
            textSize = 12f
            background = GradientDrawable().apply {
                setStroke(2, Color.LTGRAY)
                cornerRadius = 15f
            }
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(5, 0, 5, 0) }
        }
        val bAll = createQuickBtn("全选"); val bOdd = createQuickBtn("单周"); val bEven = createQuickBtn("双周")
        btnRow.addView(bAll); btnRow.addView(bOdd); btnRow.addView(bEven)
        root.addView(btnRow)

        // 🌟 5. 周次网格美化
        val weekGrid = GridLayout(this).apply { columnCount = 5 }
        val boxes = mutableListOf<CheckBox>()
        for (i in 1..25) {
            val cb = CheckBox(this).apply {
                text = "$i"
                isChecked = target.weekList.contains(i)
                buttonDrawable = null // 隐藏原生框
                gravity = Gravity.CENTER
                textSize = 13f
            }

            val updateBg = { box: CheckBox, checked: Boolean ->
                box.background = GradientDrawable().apply {
                    cornerRadius = 15f
                    if (checked) {
                        setColor(themeColor)
                        box.setTextColor(Color.WHITE)
                    } else {
                        setColor(Color.WHITE)
                        setStroke(2, Color.parseColor("#DDDDDD"))
                        box.setTextColor(Color.GRAY)
                    }
                }
            }
            updateBg(cb, cb.isChecked)
            cb.setOnCheckedChangeListener { b, checked -> updateBg(b as CheckBox, checked) }

            val glp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = 110; setMargins(8, 8, 8, 8)
            }
            cb.layoutParams = glp
            boxes.add(cb)
            weekGrid.addView(cb)
        }

        val scroll = ScrollView(this).apply {
            addView(weekGrid)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 500)
        }
        root.addView(scroll)

        // 快捷逻辑
        bAll.setOnClickListener { boxes.forEach { it.isChecked = true } }
        bOdd.setOnClickListener { boxes.forEachIndexed { i, b -> b.isChecked = (i + 1) % 2 != 0 } }
        bEven.setOnClickListener { boxes.forEachIndexed { i, b -> b.isChecked = (i + 1) % 2 == 0 } }

        // 🌟 6. 弹出对话框并应用样式
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isEdit) "修改课程信息" else "添加新课程")
            .setView(root)
            .setPositiveButton("保存") { _, _ ->
                target.name = nE.text.toString()
                target.room = rE.text.toString()
                target.teacher = tE.text.toString()
                target.weekList.clear()
                boxes.forEachIndexed { i, b -> if (b.isChecked) target.weekList.add(i + 1) }
                if (!isEdit) manualList.add(target)
                saveCoursesToLocal(fetchedList + manualList)
                selDay = -1
                viewPager.adapter?.notifyDataSetChanged()
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#F8F9FA")); cornerRadius = 40f })
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(themeColor)
    }

    // 辅助透明度函数
    private fun Int.adjustAlpha(factor: Float): Int {
        val alpha = Math.round(Color.alpha(this) * factor)
        return Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
    }

    private fun parseFinalKb(html: String) {
        val myDoc = Jsoup.parse(html)
        val detailTable = myDoc.select("table").lastOrNull() ?: return
        val rows = detailTable.select("tr")
        val rawList = mutableListOf<Course>()
        val regex = """(\d)-(\d+),(\d+)-(\d+)(\(.*\))?""".toRegex()

        for (i in 1 until rows.size) {
            val tds = rows[i].select("td")
            if (tds.size < 12) continue

            val name = tds[1].text().trim()
            val rangeWeek = tds[3].text().trim() // 原始周次如 "1-16"
            val roomList = tds[4].html().split("<br>").map { Jsoup.parse(it).text().trim() }.filter { it.isNotEmpty() }
            val timeParts = tds[5].html().split("<br>").map { Jsoup.parse(it).text().trim() }.filter { it.isNotEmpty() }
            val teacherRaw = tds[11].text().trim()
            val teacher = teacherRaw.split("\\s+".toRegex()).filter { it.isNotBlank() }.joinToString("/")

            for (idx in timeParts.indices) {
                val timeStr = timeParts[idx]
                val matches = regex.findAll(timeStr)
                val currentRoom = if (idx < roomList.size) roomList[idx] else (roomList.firstOrNull() ?: "")

                for (match in matches) {
                    val d = match.groupValues[1].toInt()
                    val s = match.groupValues[2].toInt()
                    val e = match.groupValues[4].toInt()
                    val suffix = match.groupValues[5] // 拿到 "(单)" 之类的

                    // 💡 必须调用这个函数，把字符串转成 MutableList<Int>
                    val myWeekList = convertWeeksToList(rangeWeek, suffix)

                    // 💡 这里最后一位传 myWeekList，报错就会消失
                    rawList.add(Course(name, teacher, currentRoom, d, s, e, myWeekList))
                }
            }
        }

        rawList.sortWith(compareBy({ it.day }, { it.startSection }))

        val mergedList = mutableListOf<Course>()
        for (c in rawList) {
            val last = mergedList.lastOrNull()
            // 合并逻辑：如果时间连续且名字教室一样
            if (last != null && last.name == c.name && last.day == c.day && last.room == c.room && last.endSection + 1 >= c.startSection) {
                last.endSection = maxOf(last.endSection, c.endSection)
                // 💡 核心修改：合并两个时间段的周次（去重）
                val newWeeks = (last.weekList + c.weekList).distinct().toMutableList()
                last.weekList = newWeeks
            } else {
                mergedList.add(c)
            }
        }

        runOnUiThread {
            fetchedList.clear()
            fetchedList.addAll(mergedList)

            // 保存到本地
            saveCoursesToLocal(fetchedList)

            viewPager.adapter?.notifyDataSetChanged()
            updateStatus("✅ 课表同步成功")
        }
    }

    // 将 "1-16" 和 "(单)" 转换成 [1, 3, 5...] 的列表
    private fun convertWeeksToList(rangeStr: String, suffix: String): MutableList<Int> {
        val list = mutableListOf<Int>()
        try {
            // 1. 提取开始和结束，处理 "1-16"
            val parts = rangeStr.split("-")
            if (parts.size < 2) return (1..20).toMutableList() // 兜底

            val start = parts[0].trim().toInt()
            val end = parts[1].trim().toInt()

            // 2. 遍历并根据单双周过滤
            for (i in start..end) {
                if (suffix.contains("单") && i % 2 == 0) continue
                if (suffix.contains("双") && i % 2 != 0) continue
                list.add(i)
            }
        } catch (e: Exception) {
            // 万一学校数据奇葩，默认给 1-20 周
            for (i in 1..20) list.add(i)
        }
        return list
    }

    // --- 滑动周次适配器 ---
    // --- 滑动周次适配器 ---
    inner class WeekAdapter : RecyclerView.Adapter<WeekAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_week_page, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val grid = holder.itemView.findViewById<GridLayout>(R.id.inner_grid)
            renderSingleWeek(grid, position + 1)

            // 🌟 核心修复：把全局滑动同步逻辑拿出来，放在这里！
            // 这样保证不管是第几周，只要页面一加载，必定会执行同步
            var scrollView: ScrollView? = null
            var parentView = grid.parent
            while (parentView != null) {
                if (parentView is ScrollView) {
                    scrollView = parentView
                    break
                }
                parentView = parentView.parent
            }

            scrollView?.let { sv ->
                sv.tag = "week_scroll_view" // 贴标签

                // 1. 刚翻到这一页时，立刻跳到全局记住的高度
                sv.post { sv.scrollTo(0, globalScrollY) }

                // 2. 监听当前页面的滑动，并实时广播
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    sv.setOnScrollChangeListener { view, _, scrollY, _, _ ->
                        if (globalScrollY != scrollY) {
                            globalScrollY = scrollY // 更新全局记忆

                            // 找到 ViewPager 底层的 RecyclerView
                            val rv = viewPager.getChildAt(0) as? RecyclerView
                            rv?.let { recyclerView ->
                                // 遍历预加载的其他页面，强行同步高度
                                for (i in 0 until recyclerView.childCount) {
                                    val pageView = recyclerView.getChildAt(i)
                                    val otherSv = pageView.findViewWithTag<ScrollView>("week_scroll_view")
                                    if (otherSv != null && otherSv != view && otherSv.scrollY != scrollY) {
                                        otherSv.scrollTo(0, scrollY)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount(): Int = 25
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }
    private var selDay = -1
    private var selStart = -1
    private var selEnd = -1
    private var anchorRow = -1
    private var isDragging = false

    // 💡 必须增加这两个全局变量，用于控制自动滚动的节奏
    private val autoScrollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoScrollRunnable: Runnable? = null

    private fun renderSingleWeek(grid: GridLayout, weekIdx: Int) {
        grid.removeAllViews()
        grid.rowCount = 11
        grid.columnCount = 8

        val sW = resources.displayMetrics.widthPixels
        val labelW = 70
        val cellW = (sW - labelW) / 7 - 2
        val cellH = 200

        // 1. 绘制日期表头 (修复约束冲突与隐身问题)
        val weekNames = arrayOf("", "一", "二", "三", "四", "五", "六", "日")
        val weekStartCal = Calendar.getInstance()
        if (semesterStartMillis != 0L) {
            weekStartCal.timeInMillis = semesterStartMillis + (weekIdx - 1) * 7 * 24 * 3600000L
        }

        // 🌟 修复：高度改用 50dp，解决 110 像素太矮的问题
        val density = resources.displayMetrics.density
        val headerH = (50 * density).toInt()

        for (i in 0..7) {
            val box = LinearLayout(this).apply {
                setOrientation(LinearLayout.VERTICAL) // 🌟 必须垂直，否则日期星期横着挤
                setGravity(Gravity.CENTER)
            }

            // 🌟 关键：去掉 spec 里的 1f，手动管理宽度，解决 Logcat 里的 Inconsistent 报错
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(0), // 第 0 行
                GridLayout.spec(i)  // 第 i 列
            )
            lp.width = if (i == 0) labelW else cellW
            lp.height = headerH
            box.setLayoutParams(lp)

            if (i == 0) {
                val tvW = TextView(this).apply {
                    setText("$weekIdx\n周")
                    setTextSize(10f)
                    setTextColor(Color.parseColor("#757575")) // 强制深灰
                    setGravity(Gravity.CENTER)
                }
                box.addView(tvW)
            } else {
                val dCal = Calendar.getInstance()
                dCal.timeInMillis = weekStartCal.timeInMillis + (i - 1) * 24 * 3600000L
                val dStr = "${dCal.get(Calendar.MONTH) + 1}.${dCal.get(Calendar.DAY_OF_MONTH)}"


                val tvD = TextView(this).apply {
                    setText(dStr)
                    setTextSize(10f)
                    setTextColor(Color.parseColor("#999999")) // 强制灰色
                    setGravity(Gravity.CENTER)
                    setLayoutParams(LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }

                val tvDay = TextView(this).apply {
                    setText(weekNames[i])
                    setTextSize(13f)
                    setTextColor(Color.BLACK) // 🌟 强制黑色，防止隐身
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setGravity(Gravity.CENTER)
                    setLayoutParams(LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }

                box.addView(tvD)
                box.addView(tvDay)

                // 今天高亮逻辑
                val today = Calendar.getInstance()
                val todayStr = "${today.get(Calendar.MONTH) + 1}.${today.get(Calendar.DAY_OF_MONTH)}"
                if (dStr == todayStr && semesterStartMillis != 0L) {
                    box.setBackground(GradientDrawable().apply {
                        // 🌟 改为纯蓝色背景，去掉边框
                        setColor(Color.parseColor("#2196F3"))
                        cornerRadius = 20f // 圆角调大一点更灵动
                    })
                    // 同时也把里面的文字颜色改了（需要遍历 box 里的 TextView）
                    for (idx in 0 until box.childCount) {
                        (box.getChildAt(idx) as? TextView)?.setTextColor(Color.WHITE)
                    }
                }
            }
            grid.addView(box)
        }

        // 2. 侧边 1-10 节次
        // 2. 侧边 1-10 节次（增加具体时间段显示）
        val timeSlots = mapOf(
            1 to "08:10\n08:55",
            2 to "09:05\n09:50",
            3 to "10:10\n10:55",
            4 to "11:05\n11:50",
            5 to "14:20\n15:05",
            6 to "15:15\n16:00",
            7 to "16:20\n17:05",
            8 to "17:15\n18:00",
            9 to "19:30\n20:15",
            10 to "20:25\n21:10"
        )

        for (i in 1..10) {
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // 🌟 移除背景颜色，保持透明，让视觉更通透
                val lp = GridLayout.LayoutParams(GridLayout.spec(i), GridLayout.spec(0))
                lp.width = labelW
                lp.height = cellH
                layoutParams = lp
            }

            // 上方的节次数字：加粗，颜色稍深
            val tvIndex = TextView(this).apply {
                text = "$i"
                textSize = 13f
                setTextColor(Color.parseColor("#333333")) // 深灰色，比纯黑高级
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }

            // 下方的具体时间段：更小一点，颜色变淡，拉开层级
            val tvTime = TextView(this).apply {
                text = timeSlots[i]
                textSize = 8.5f
                setTextColor(Color.parseColor("#AAAAAA")) // 浅灰色，作为辅助信息
                gravity = Gravity.CENTER
                setLineSpacing(0f, 0.9f) // 紧凑一点
                setPadding(0, 2, 0, 0)
            }

            wrapper.addView(tvIndex)
            wrapper.addView(tvTime)
            grid.addView(wrapper)
        }

        // 3. 占用矩阵与画课
        val occupied = Array(11) { BooleanArray(8) }

        val all = fetchedList + manualList
        for (c in all) {
            // 1. 过滤与占用标记
            if (!c.weekList.contains(weekIdx) || c.startSection > 10 || c.day > 7) continue
            for (r in c.startSection..c.endSection) {
                if (r <= 10) occupied[r][c.day] = true
            }

            val card = TextView(this)
            val sn = if (c.name.length > 8) c.name.take(7) + ".." else c.name
            card.text = "${sn}\n@${c.room}"

            // ———————————————— 🌟 核心改进：黄金分割颜色算法 ————————————————
            // 使用课程名作为随机数种子，确保同一门课颜色固定，不同课颜色随机
            val seed = c.name.hashCode().toLong()
            val random = java.util.Random(seed)

            // 1. 随机色相 (0-360)
            val hue = random.nextInt(360).toFloat()
            // 2. 这里的饱和度和亮度手动固定，保证“多巴胺”浅色质感
            val saturation = 0.5f
            val lightness = 0.9f

            // 转换颜色
            val bgInt = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))
            val textInt = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, saturation, 0.25f))

            card.setTextColor(textInt)
            card.textSize = 11f
            card.setTypeface(null, android.graphics.Typeface.BOLD)
            card.gravity = Gravity.CENTER
            card.setPadding(10, 10, 10, 10)

            val shape = GradientDrawable().apply {
                cornerRadius = 24f //
                setColor(bgInt)
                setStroke(3, textInt) // 边框加粗增强视觉隔离
            }
            card.background = shape

// 🌟 增加点击水波纹效果
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            card.foreground = getDrawable(outValue.resourceId)

            card.setOnClickListener {
                selDay = -1
                grid.post { renderSingleWeek(grid, weekIdx) }
                showCourseDetailDialog(c)
            }

            // 设置布局参数
            val span = minOf(c.endSection, 10) - c.startSection + 1
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(c.startSection, span, GridLayout.FILL),
                GridLayout.spec(c.day, 1, GridLayout.FILL)
            )

            // 调整间距，让卡片之间有“呼吸感”
            lp.width = cellW - 8
            lp.height = cellH * span - 8
            lp.setMargins(4, 4, 4, 4)
            lp.setGravity(Gravity.FILL)
            card.layoutParams = lp

            grid.addView(card)
        }

        // --- 4. 蓝框绘制与边缘自动滚动核心逻辑 ---
        if (selDay != -1) {
            val selBox = FrameLayout(this)
            selBox.tag = "selection_box"
            val shape = GradientDrawable()
            shape.setStroke(6, Color.parseColor("#2196F3"))
            shape.setColor(Color.parseColor("#252196F3"))
            shape.cornerRadius = 12f
            selBox.background = shape

            val plus = TextView(this)
            plus.text = "+"
            plus.textSize = 32f
            plus.setTextColor(Color.parseColor("#2196F3"))
            plus.gravity = Gravity.CENTER
            plus.setTypeface(null, android.graphics.Typeface.BOLD)
            plus.visibility = if (isDragging) View.INVISIBLE else View.VISIBLE
            selBox.addView(plus, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

            var sRawY = 0f
            var sScrollY = 0

            // 动态向上查找 ScrollView
            var scrollView: ScrollView? = null
            var parentView = grid.parent
            while (parentView != null) {
                if (parentView is ScrollView) {
                    scrollView = parentView
                    break
                }
                parentView = parentView.parent
            }

            var currentScrollSpeed = 0
            var isAutoScrolling = false

            // 🌟 新增：保存当前手指的绝对位置，防止自动滚动时坐标乱飞导致蓝框回缩
            var currentRawY = 0f

            selBox.setOnTouchListener { v, event ->
                currentRawY = event.rawY // 🌟 每次触摸都实时更新

                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        sRawY = event.rawY
                        sScrollY = scrollView?.scrollY ?: 0
                        isDragging = true
                        plus.visibility = View.INVISIBLE
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val currentScroll = scrollView?.scrollY ?: 0
                        val deltaY = (currentRawY - sRawY) + (currentScroll - sScrollY)

                        scrollView?.let { sv ->
                            val location = IntArray(2)
                            sv.getLocationOnScreen(location)
                            val svTop = location[1]
                            val svBottom = svTop + sv.height

                            currentScrollSpeed = if (currentRawY > svBottom - 150) 25
                            else if (currentRawY < svTop + 150) -25
                            else 0

                            if (currentScrollSpeed != 0) {
                                if (!isAutoScrolling) {
                                    isAutoScrolling = true
                                    autoScrollRunnable = object : Runnable {
                                        override fun run() {
                                            if (currentScrollSpeed == 0 || !isDragging) {
                                                isAutoScrolling = false
                                                return
                                            }
                                            sv.scrollBy(0, currentScrollSpeed)

                                            // 🌟 使用 currentRawY 计算偏移量
                                            val moveDelta = (currentRawY - sRawY) + (sv.scrollY - sScrollY)
                                            val off = (moveDelta / cellH).toInt()
                                            val cR = (anchorRow + off).coerceIn(1, 10)
                                            val nS = minOf(anchorRow, cR)
                                            val nE = maxOf(anchorRow, cR)

                                            var blocked = false
                                            for (i in nS..nE) { if (occupied[i][selDay]) blocked = true }
                                            if (!blocked) {
                                                selStart = nS
                                                selEnd = nE
                                                updateSelectionBox(grid, cellW, cellH)
                                            }

                                            autoScrollHandler.postDelayed(this, 16)
                                        }
                                    }
                                    autoScrollHandler.post(autoScrollRunnable!!)
                                }
                            } else {
                                currentScrollSpeed = 0
                                isAutoScrolling = false
                                autoScrollRunnable?.let { autoScrollHandler.removeCallbacks(it) }
                            }
                        }

                        val rowOffset = (deltaY / cellH).toInt()
                        val curR = (anchorRow + rowOffset).coerceIn(1, 10)
                        val nS = minOf(anchorRow, curR)
                        val nE = maxOf(anchorRow, curR)

                        var blocked = false
                        for (i in nS..nE) { if (occupied[i][selDay]) blocked = true }
                        if (!blocked && (nS != selStart || nE != selEnd)) {
                            selStart = nS
                            selEnd = nE
                            updateSelectionBox(grid, cellW, cellH)
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        currentScrollSpeed = 0
                        isAutoScrolling = false
                        autoScrollRunnable?.let { autoScrollHandler.removeCallbacks(it) }
                        v.parent.requestDisallowInterceptTouchEvent(false)

                        // 🌟 核心修复：直接弹出添加框！不要再给用户第二次点击去“缩小”它的机会了！
                        showCourseEditDialog(null, selDay, selStart, selEnd)
                    }
                }
                true
            }
            updateSelectionBoxParams(selBox, cellW, cellH)
            grid.addView(selBox)
        }

        // 5. 感应层
        for (r in 1..10) {
            for (cl in 1..7) {
                if (occupied[r][cl] || (cl == selDay && r >= selStart && r <= selEnd)) continue
                val sensor = View(this)
                sensor.setOnClickListener {
                    selDay = cl
                    anchorRow = r
                    selStart = r
                    selEnd = r
                    grid.post { renderSingleWeek(grid, weekIdx) }
                }
                grid.addView(sensor, GridLayout.LayoutParams(GridLayout.spec(r), GridLayout.spec(cl)).apply {
                    width = cellW
                    height = cellH
                })
            }
        } // 🌟 循环在这里结束

        // 2. 循环结束后，统一更新一次锁屏看板
        // --- 🌟 唯一正确的通知更新逻辑：必须放在 renderSingleWeek 结尾大括号前 ---
        // --- 🌟 暴力找回通知版：删掉了所有复杂的判断 ---
        val now = Calendar.getInstance()
        val today = ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

        // 只要是“本周”页面，我们就发通知
        // --- 🌟 智能通知看板：上课显当前，下课显下节 ---
        // --- 🌟 智能判定：进行中 VS 即将开始 ---
        if (weekIdx == currentWeek) {
            val now = Calendar.getInstance()
            val today = ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
            val currentTime = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            val todayCourses = (fetchedList + manualList).filter {
                it.day == today && it.weekList.contains(weekIdx)
            }.sortedBy { it.startSection }

            if (todayCourses.isNotEmpty()) {
                val displayCourse = todayCourses.find {
                    val endTime = when(it.endSection) {
                        1 -> 8*60+55; 2 -> 9*60+50; 3 -> 10*60+55; 4 -> 11*60+50; 5 -> 15*60+5;
                        6 -> 16*60+0; 7 -> 17*60+5; 8 -> 18*60+0; 9 -> 20*60+15; 10 -> 21*60+10
                        else -> 0
                    }
                    currentTime < endTime
                } ?: todayCourses.first()

                // 🌟 计算状态
                val startTime = when(displayCourse.startSection) {
                    1 -> 8*60+10; 2 -> 9*60+5; 3 -> 10*60+10; 4 -> 11*60+5; 5 -> 14*60+20;
                    6 -> 15*60+15; 7 -> 16*60+20; 8 -> 17*60+15; 9 -> 19*60+30; 10 -> 20*60+25
                    else -> 0
                }

                val status = if (currentTime >= startTime) "进行中" else "即将开始"

                val seed = displayCourse.name.hashCode().toLong()
                val hue = (java.util.Random(seed).nextInt(360)).toFloat()
                val notifBgInt = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, 0.8f, 0.75f))
                val timeRange = "${getTimeRange(displayCourse.startSection).split(" - ")[0]} - ${getTimeRange(displayCourse.endSection).split(" - ")[1]}"

                // 🌟 传入 4 个参数
                showLockScreenCourse(
                    displayCourse.name,
                    "${displayCourse.room} | $timeRange",
                    notifBgInt,
                    status
                )
            }
        }
    } // renderSingleWeek 结束 // 函数结束 // 🌟 这里是 renderSingleWeek 的最后一个大括号
    // 💡 辅助：详情弹窗（带右上角编辑按钮）
    private fun showCourseDetailDialog(c: Course) {
        // 1. 🌟 获取这节课的专属颜色（与课表格子保持绝对统一）
        val seed = c.name.hashCode().toLong()
        val random = java.util.Random(seed)
        val hue = random.nextInt(360).toFloat()
        // 这里的饱和度和亮度建议与课表一致，或者稍微加深一点点（0.85f）显质感
        val themeBgColor = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, 0.5f, 0.9f))
        val themeTextColor = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, 0.5f, 0.3f))

        // 2. 🌟 创建自定义标题容器
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(60, 50, 60, 20)
            // 顶部带个小色块修饰
            background = GradientDrawable().apply {
                setColor(themeBgColor)
                // 只有上方两个圆角
                cornerRadii = floatArrayOf(48f, 48f, 48f, 48f, 0f, 0f, 0f, 0f)
            }
        }

        val tvTitle = TextView(this).apply {
            text = c.name
            textSize = 18f
            setTextColor(themeTextColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }

        val btnEdit = TextView(this).apply {
            text = "编辑"
            setTextColor(themeTextColor)
            textSize = 14f
            setPadding(30, 10, 30, 10)
            // 给编辑按钮加一个半透明小边框
            background = GradientDrawable().apply {
                setStroke(2, themeTextColor)
                cornerRadius = 100f
            }
        }
        titleLayout.addView(tvTitle)
        titleLayout.addView(btnEdit)

        // 3. 🌟 构建内容区域
        val scroll = ScrollView(this)
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 60)
        }

        val infoText = "👤 教师：${c.teacher}\n📍 教室：${c.room}\n⏰ 节次：周${c.day} 第${c.startSection}-${c.endSection}节"
        val tvContent = TextView(this).apply {
            text = infoText
            textSize = 15f
            setTextColor(Color.parseColor("#666666"))
            setLineSpacing(15f, 1f) // 增加行间距，不拥挤
        }
        contentLayout.addView(tvContent)
        scroll.addView(contentLayout)

        // 4. 🌟 创建并应用大圆角样式
        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(titleLayout)
            .setView(scroll)
            .setPositiveButton("我知道了", null)
            .setNeutralButton("删除课程") { _, _ ->
                fetchedList.remove(c)
                manualList.remove(c)
                saveCoursesToLocal(fetchedList + manualList)
                viewPager.adapter?.notifyDataSetChanged()
            }
            .create()

        // 🌟 核心美化：强制去除系统默认白框，应用自定义大圆角背景
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 48f // 超大圆角，非常有现代感
        })

        btnEdit.setOnClickListener {
            dialog.dismiss()
            showCourseEditDialog(c)
        }

        dialog.show()

        // 5. 🌟 顺便美化底部的按钮颜色
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(themeTextColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.RED)
    }

    // 💡 保证这两函数也存在且逻辑正确
    private fun updateSelectionBox(grid: GridLayout, cellW: Int, cellH: Int) {
        val v = grid.findViewWithTag<View>("selection_box") ?: return
        updateSelectionBoxParams(v, cellW, cellH)
    }

    private fun updateSelectionBoxParams(v: View, cW: Int, cH: Int) {
        val span = selEnd - selStart + 1
        val lp = GridLayout.LayoutParams(
            GridLayout.spec(selStart, span, GridLayout.FILL),
            GridLayout.spec(selDay, 1, GridLayout.FILL)
        )
        lp.width = cW - 6
        lp.height = cH * span - 6
        lp.setMargins(3, 3, 3, 3)
        lp.setGravity(Gravity.FILL)
        v.layoutParams = lp
    }

    private fun calculateCurrentWeek() {
        if (semesterStartMillis == 0L) return

        val now = Calendar.getInstance()
        val start = Calendar.getInstance().apply { timeInMillis = semesterStartMillis }

        now.set(Calendar.HOUR_OF_DAY, 0)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)

        val diffMillis = now.timeInMillis - start.timeInMillis
        val diffDays = diffMillis / (1000 * 60 * 60 * 24)

        currentWeek = (diffDays / 7).toInt() + 1
        if (currentWeek < 1) currentWeek = 1

        runOnUiThread {
            updateStatus("✅ 当前周：第 $currentWeek 周")
            // 🌟 核心修复：同步更新顶部标题栏文字与颜色
            val tvTopWeek = findViewById<TextView>(R.id.tv_top_week)
            tvTopWeek.text = "第 ${currentWeek} 周(本周)"
            tvTopWeek.setTextColor(Color.parseColor("#2196F3")) // 蓝色高亮本周
        }
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        android.app.DatePickerDialog(this, { _, y, m, d ->
            val start = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }
            semesterStartMillis = start.timeInMillis
            getEncryptedPrefs().edit().putLong("start_date", semesterStartMillis).apply()
            calculateCurrentWeek()
            // 检查并提示用户开启锁屏看板权限
            checkNotificationPermission()
            viewPager.adapter?.notifyDataSetChanged()
            viewPager.setCurrentItem(currentWeek - 1, false)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
    // 保存课表到手机本地
    private fun saveCoursesToLocal(courses: List<Course>) {
        try {
            val gson = Gson()
            val jsonString = gson.toJson(courses)
            getEncryptedPrefs().edit()
                .putString("saved_kb", jsonString)
                .apply()
        } catch (e: Exception) {
            Log.e("SAVE_ERROR", "保存失败: ${e.message}")
        }
    }

    // 从手机本地读取保存过的课表
    private fun loadCoursesFromLocal() {
        val json = getEncryptedPrefs().getString("saved_kb", null) ?: return
        try {
            val sType = object : TypeToken<List<Course>>() {}.type
            val list: List<Course> = Gson().fromJson(json, sType)
            fetchedList.clear()
            fetchedList.addAll(list)
            runOnUiThread {
                viewPager.adapter?.notifyDataSetChanged()
                // 🌟 核心修改：数据加载完，立刻刷一次通知
                refreshNotificationOnly()
                updateStatus("✅ 已载入离线课表")
            }
        } catch (e: Exception) {
            getEncryptedPrefs().edit().remove("saved_kb").apply()
        }
    }


    private fun decodeGb2312(r: Response) = String(r.body?.bytes() ?: byteArrayOf(), Charset.forName("GB2312"))
    // 💡 替换原有的 updateStatus
    private fun updateStatus(s: String) {
        runOnUiThread {
            // 1. 只有“成功”、“失败”、“错误”这种对用户有用的结论，才用 Toast 弹窗提示
            if (s.contains("成功") || s.contains("失败") || s.contains("错误") || s.contains("故障")) {
                Toast.makeText(this@MainActivity, s, Toast.LENGTH_SHORT).show()
            }
            // 2. 像“正在拉取”、“解析中”这种废话，直接打进 Logcat，只有你自己插电脑上调试时才看得到
            Log.d("AppStatus", s)
        }
    }

    // 1. 抓取培养方案并精准解析每一列
    // 🌟 终极 POST 动态翻页版爬虫
    // 🌟 终极版：支持自动 POST 翻页、异常拦截、数据持久化存储的培养方案爬虫
    private fun fetchTrainingPlanData(
        targetUrl: String,
        currentPage: Int = 1,
        accumulatedList: MutableList<TrainingPlan> = mutableListOf(),
        previousDoc: org.jsoup.nodes.Document? = null
    ) {
        // 1. 在主线程初始化 UI 状态
        runOnUiThread {
            if (currentPage == 1) {
                // 只有第一页开始时清空本地仓库和容器
                fullTrainingPlanList.clear()
                val container = findViewById<LinearLayout>(R.id.plan_container)
                container?.removeAllViews()
            }
            updateStatus("正在拉取培养方案 (第 $currentPage 页)...")
        }

        // 2. 构造请求：第一页用 GET，后续页面用 POST 伪造表单
        val requestBuilder = Request.Builder()
            .url(targetUrl)
            .addHeader("Referer", "https://jiaowu.sicau.edu.cn/jiaoshi/bangong/index1.asp")

        if (currentPage > 1 && previousDoc != null) {
            val formBuilder = FormBody.Builder()
            // 关键：自动从上一页 HTML 提取所有隐藏表单字段（验证码、Token等）
            val inputs = previousDoc.select("input")
            for (input in inputs) {
                val name = input.attr("name")
                val value = input.attr("value")
                if (name.isNotEmpty()) {
                    // 动态修改页码参数 y（你抓包发现的关键点）
                    if (name == "y" || name == "page") {
                        formBuilder.add(name, currentPage.toString())
                    } else {
                        formBuilder.add(name, value)
                    }
                }
            }
            // 确保包含必要的 title 标识
            formBuilder.add("title_id1", "1")
            requestBuilder.post(formBuilder.build())
        }

        myClient.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    updateStatus("网络请求失败，请检查VPN连接")
                    // 即使失败，也将目前已抓到的部分展示出来
                    renderPlanCards(accumulatedList)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val html = decodeGb2312(response)
                    val doc = Jsoup.parse(html, targetUrl)

                    // 3. 地毯式搜索当前页面的所有课程数据行
                    val allRows = doc.select("tr")
                    for (row in allRows) {
                        val cells = row.children()
                        // 宽容解析：只要超过 15 列就认为是课程行
                        if (cells.size >= 15) {
                            val courseName = cells.getOrNull(2)?.text()?.trim() ?: ""
                            // 排除表头和空行
                            if (courseName.isNotEmpty() && !courseName.contains("课程名称")) {
                                accumulatedList.add(TrainingPlan(
                                    id = cells.getOrNull(1)?.text()?.trim() ?: "",
                                    name = courseName,
                                    enName = cells.getOrNull(3)?.text()?.trim() ?: "",
                                    nature = cells.getOrNull(4)?.text()?.trim() ?: "",
                                    system = cells.getOrNull(5)?.text()?.trim() ?: "",
                                    credit = cells.getOrNull(6)?.text()?.trim() ?: "",
                                    totalHours = cells.getOrNull(7)?.text()?.trim() ?: "",
                                    lectureHours = cells.getOrNull(8)?.text()?.trim() ?: "",
                                    labHours = cells.getOrNull(9)?.text()?.trim() ?: "",
                                    selfStudyHours = cells.getOrNull(11)?.text()?.trim() ?: "0",
                                    term = cells.getOrNull(22)?.text()?.trim() ?: "未知"
                                ))
                            }
                        }
                    }

                    // 4. 判断是否需要翻页（寻找下一页数字按钮）
                    val targetNextText = (currentPage + 1).toString()
                    val nextLinkElement = doc.select("a").firstOrNull {
                        it.text().trim() == targetNextText || it.text().trim() == "[$targetNextText]"
                    }

                    if (nextLinkElement != null) {
                        // 发现下一页，递归继续抓取
                        fetchTrainingPlanData(targetUrl, currentPage + 1, accumulatedList, doc)
                    } else {
                        // 全部抓取完毕
                        runOnUiThread {
                            if (accumulatedList.isEmpty()) {
                                updateStatus("未找到数据，请确认是否已登录")
                            } else {
                                updateStatus("加载完成：共获取 ${accumulatedList.size} 门课程")
                                // 将全量数据同步到搜索仓库
                                fullTrainingPlanList.clear()
                                fullTrainingPlanList.addAll(accumulatedList)
                                // 初始渲染全部卡片
                                renderPlanCards(fullTrainingPlanList)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Crawler", "解析第 $currentPage 页出错: ${e.message}")
                    runOnUiThread { renderPlanCards(accumulatedList) }
                }
            }
        })
    }

    // 2. 将数据画成漂亮的卡片
    private fun renderPlanCards(plans: List<TrainingPlan>) {
        val container = findViewById<LinearLayout>(R.id.plan_container) ?: return
        container.removeAllViews()

        findViewById<TextView>(R.id.tv_credit_content)?.let {
            it.setVisibility(View.GONE)
        }

        // 🌟 1. 改用 forEachIndexed，它是实现“顺次滑出”动画的前提
        plans.forEachIndexed { index, p ->
            val card = LinearLayout(this).apply {
                setOrientation(LinearLayout.VERTICAL)
                setPadding(40, 35, 40, 35)
                val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                lp.setMargins(0, 0, 0, 30)
                setLayoutParams(lp)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setCornerRadius(30f)
                }
                setElevation(4f)
            }

            // --- 这一部分是你原来的 UI 布局逻辑，100% 保持不动 ---
            val topRow = RelativeLayout(this).apply {
                setLayoutParams(LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val tvCredit = TextView(this).apply {
                setId(View.generateViewId())
                setText("(${p.credit}学分)"); setTextSize(14f); setTextColor(Color.parseColor("#2196F3"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                val lp = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                lp.addRule(RelativeLayout.ALIGN_PARENT_END); lp.addRule(RelativeLayout.CENTER_VERTICAL)
                setLayoutParams(lp)
            }
            val tvName = TextView(this).apply {
                setText(p.name); setTextSize(16f); setTextColor(Color.parseColor("#222222"))
                setTypeface(null, android.graphics.Typeface.BOLD); setSingleLine(true); setEllipsize(android.text.TextUtils.TruncateAt.END)
                val lp = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                lp.addRule(RelativeLayout.ALIGN_PARENT_START); lp.addRule(RelativeLayout.START_OF, tvCredit.getId()); lp.setMarginEnd(20)
                setLayoutParams(lp)
            }
            topRow.addView(tvCredit); topRow.addView(tvName)
            val tvEnName = TextView(this).apply {
                setText(if (p.enName.isEmpty()) "无英文名" else p.enName); setTextSize(12f); setTextColor(Color.parseColor("#999999"))
                setSingleLine(true); setEllipsize(android.text.TextUtils.TruncateAt.END); setPadding(0, 8, 0, 16)
            }
            val bottomRow = RelativeLayout(this).apply { setLayoutParams(LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
            bottomRow.addView(TextView(this).apply {
                setText("编号:${p.id} | 性质:${p.nature} | 第${p.term}学期"); setTextSize(12f); setTextColor(Color.parseColor("#777777"))
                layoutParams = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_START) }
            })
            bottomRow.addView(TextView(this).apply {
                setText("详细 >"); setTextSize(13f); setTextColor(Color.parseColor("#2196F3")); setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_END) }
            })
            card.addView(topRow); card.addView(tvEnName); card.addView(bottomRow)
            // ---------------------------------------------------

            val listener = View.OnClickListener { showPlanDetailDialog(p) }
            card.setOnClickListener(listener)

            // 🌟 2. 先把卡片加进容器
            container.addView(card)

            // 🌟 3. 给前 15 个卡片注入“生命力”：入场动画
            if (index < 15) {
                card.alpha = 0f         // 初始透明
                card.translationY = 80f // 初始位置向下偏

                card.animate()
                    .alpha(1f)          // 变不透明
                    .translationY(0f)   // 回到原位
                    .setDuration(400)   // 持续时间
                    .setStartDelay((index * 40).toLong()) // 阶梯延迟：每个卡片晚 40ms
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    // 3. 弹出详细信息对话框
    private fun showPlanDetailDialog(p: TrainingPlan) {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
        }

        val title = TextView(this).apply {
            text = p.name
            textSize = 20f
            setTextColor(Color.parseColor("#1A237E"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }

        val contentText = """
        英文名：${if(p.enName.isEmpty()) "暂无" else p.enName}
        课程编号：${p.id}
        课程性质：${p.nature}
        课程体系：${p.system}
        学分：${p.credit}
        总学时：${p.totalHours} (讲授 ${p.lectureHours} / 实验 ${p.labHours})
        执行学期：第 ${p.term} 学期
    """.trimIndent()

        val content = TextView(this).apply {
            // 1. 使用 setText 方法
            setText(contentText)

            // 2. 🌟 关键：改用 setTextSize() 方法，不使用 textSize = ...
            setTextSize(14f)

            // 3. 🌟 关键：改用 setLineSpacing() 方法
            // 第一个参数是 extra（行间距增量），第二个参数是 multiplier（倍数，通常传 1.0f）
            setLineSpacing(12f, 1.0f)

            // 4. 使用 setTextColor 方法
            setTextColor(Color.parseColor("#546E7A"))
        }

        layout.addView(title)
        layout.addView(content)
        scroll.addView(layout)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("确 定", null)
            .create().apply {
                // 设置弹窗自身的圆角（需要窗口支持）
                window?.setBackgroundDrawable(GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 48f
                })
            }.show()
    }

    private fun showForgotPasswordDialog() {
        // 🌟 1. 创建大圆角背景容器
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8F9FA")) // 浅灰底色
            }
        }

        // 🌟 2. 增加温馨提示头
        val tvHint = TextView(this).apply {
            text = "🔒 身份核验\n请填写以下信息以找回教务系统密码"
            textSize = 14f
            setTextColor(Color.GRAY)
            setLineSpacing(10f, 1f)
            setPadding(0, 0, 0, 40)
        }
        root.addView(tvHint)

        // 🌟 3. 白色卡片式输入区域
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 10, 30, 10)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 30f
            }
            elevation = 4f
        }

        fun createInput(hintText: String, inputTypeInt: Int): EditText {
            return EditText(this).apply {
                hint = hintText
                inputType = inputTypeInt
                background = null // 去掉原生下划线
                textSize = 15f
                setPadding(20, 40, 20, 40)
                setHintTextColor(Color.LTGRAY)
            }
        }

        val etId = createInput("学号 (例: 202xxxxx)", android.text.InputType.TYPE_CLASS_NUMBER)
        val etIdCard = createInput("身份证号 (末位 X 请用大写)", android.text.InputType.TYPE_CLASS_TEXT)
        val etPhone = createInput("预留手机号 (11位数字)", android.text.InputType.TYPE_CLASS_PHONE)

        card.addView(etId)
        card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 2); setBackgroundColor(Color.parseColor("#EEEEEE")) })
        card.addView(etIdCard)
        card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 2); setBackgroundColor(Color.parseColor("#EEEEEE")) })
        card.addView(etPhone)

        root.addView(card)

        // 🌟 4. 底部声明
        val tvSafe = TextView(this).apply {
            text = "ℹ️ 信息将加密传输至学校教务处服务器"
            textSize = 11f
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 0)
        }
        root.addView(tvSafe)

        // 🌟 5. 创建对话框

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("找回密码")
            .setView(root)
            .setPositiveButton("立即核验", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.parseColor("#F8F9FA")); cornerRadius = 50f
        })

        dialog.show()

        val btnConfirm = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        btnConfirm.setTextColor(Color.parseColor("#2196F3"))

        btnConfirm.setOnClickListener {
            val id = etId.text.toString().trim()
            val idCard = etIdCard.text.toString().trim()
            val phone = etPhone.text.toString().trim()

            // 1. 本地初步校验（不通过时不关弹窗）
            if (id.isEmpty() || phone.length != 11 || (idCard.length != 15 && idCard.length != 18)) {
                Toast.makeText(this, "输入格式有误，请检查学号、身份证或手机号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 2. 开始联网核验
            btnConfirm.isEnabled = false
            btnConfirm.text = "核验中..."

            val realUrl = "https://jiaowu.sicau.edu.cn/jiaoshi/bangong/mima_cha.asp"
            val body = FormBody.Builder(java.nio.charset.Charset.forName("GB2312"))
                .add("password1", id).add("lb", "S").add("password2", idCard).add("password3", phone).add("submit1", "查询")
                .build()

            val request = Request.Builder().url(realUrl).post(body)
                .addHeader("Referer", "https://jiaowu.sicau.edu.cn/web/web/web/Looking_pwd.htm").build()

            myClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "网络异常，请重试", Toast.LENGTH_SHORT).show()
                        btnConfirm.isEnabled = true
                        btnConfirm.text = "立即核验"
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val html = decodeGb2312(response)
                    val doc = org.jsoup.Jsoup.parse(html)

                    // 1. 精准提取报错信息
                    val errorFromScript = if (html.contains("alert('")) html.substringAfter("alert('").substringBefore("')") else ""
                    val errorFromFont = doc.select("font[color=red]").text().trim()
                    val rawReason = when {
                        errorFromScript.isNotEmpty() -> errorFromScript
                        errorFromFont.isNotEmpty() -> errorFromFont
                        else -> doc.text().take(60)
                    }

                    runOnUiThread {
                        fun showPrettyResult(isSuccess: Boolean, message: String) {
                            val themeColor = if (isSuccess) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")

                            // 外层容器：纯白圆角卡片
                            val resultView = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                setPadding(60, 80, 60, 60)
                                background = GradientDrawable().apply {
                                    setColor(Color.WHITE)
                                    cornerRadius = 60f
                                }
                            }

                            // 🌟 自绘制图标：圆形背景 + 文字（替代丑丑的 Emoji）
                            val iconFrame = FrameLayout(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(160, 160).apply { setMargins(0, 0, 0, 40) }
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(themeColor)
                                }
                            }
                            val tvIconInner = TextView(this@MainActivity).apply {
                                text = if (isSuccess) "✓" else "!" // 简洁的符号
                                textSize = 32f
                                setTextColor(Color.WHITE)
                                gravity = Gravity.CENTER
                                setTypeface(null, Typeface.BOLD)
                            }
                            iconFrame.addView(tvIconInner)

                            // 状态标题
                            val tvStatus = TextView(this@MainActivity).apply {
                                text = if (isSuccess) "核验成功" else "核验失败"
                                textSize = 19f
                                setTextColor(Color.BLACK)
                                setTypeface(null, Typeface.BOLD)
                                setPadding(0, 0, 0, 15)
                            }

                            // 描述文字
                            val tvDesc = TextView(this@MainActivity).apply {
                                text = message
                                textSize = 14f
                                setTextColor(Color.parseColor("#757575"))
                                gravity = Gravity.CENTER
                                setLineSpacing(8f, 1f)
                            }

                            resultView.addView(iconFrame)
                            resultView.addView(tvStatus)
                            resultView.addView(tvDesc)

                            // 🌟 增加一个优雅的关闭按钮（仅失败时显示）
                            if (!isSuccess) {
                                val btnClose = TextView(this@MainActivity).apply {
                                    text = "返回修改"
                                    setTextColor(themeColor)
                                    textSize = 15f
                                    setPadding(0, 40, 0, 0)
                                    setTypeface(null, Typeface.BOLD)
                                    setOnClickListener { /* 弹窗会自动随点击消失 */ }
                                }
                                resultView.addView(btnClose)
                            }

                            val resDialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                .setView(resultView)
                                .create()

                            resDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                            resDialog.show()

                            if (isSuccess) {
                                resultView.postDelayed({
                                    resDialog.dismiss()
                                    dialog.dismiss()
                                }, 2000)
                            } else {
                                // 失败时点击任何地方都关闭反馈框，回到输入框
                                resultView.setOnClickListener { resDialog.dismiss() }
                            }
                        }

                        // --- 逻辑判断分水岭 ---
                        if (html.contains("成功") || html.contains("匹配") || html.contains("window.close")) {
                            showPrettyResult(true, "核验成功！新密码将通过短信发送，请注意查收。")
                        } else {
                            btnConfirm.isEnabled = true
                            btnConfirm.text = "立即核验"

                            val finalReason = if (rawReason.isBlank()) "信息核验失败，请检查输入" else rawReason
                            showPrettyResult(false, finalReason)

                            tvHint.text = "⚠️ 核验失败：$finalReason"
                            tvHint.setTextColor(Color.parseColor("#F44336"))
                        }
                    }
                }
            })
        }
    }

    // 🌟 这里的参数增加了 dialog, btnConfirm 和 tvHint
    private fun doLookingPwd(id: String, idCard: String, phone: String,
                             dialog: androidx.appcompat.app.AlertDialog,
                             btnConfirm: Button,
                             tvHint: TextView) {

        btnConfirm.isEnabled = false
        btnConfirm.text = "核验中..."

        val realUrl = "https://jiaowu.sicau.edu.cn/jiaoshi/bangong/mima_cha.asp"
        val body = FormBody.Builder(java.nio.charset.Charset.forName("GB2312"))
            .add("password1", id)
            .add("lb", "S")
            .add("password2", idCard)
            .add("password3", phone)
            .add("submit1", "查询")
            .build()

        val request = Request.Builder()
            .url(realUrl)
            .post(body)
            .addHeader("Referer", "https://jiaowu.sicau.edu.cn/web/web/web/Looking_pwd.htm")
            .build()

        myClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread {
                    btnConfirm.isEnabled = true
                    btnConfirm.text = "立即核验"
                    Toast.makeText(this@MainActivity, "网络异常", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val html = decodeGb2312(response)
                val doc = org.jsoup.Jsoup.parse(html)

                // 抓取报错原因
                val errorMsg = if (html.contains("alert('")) {
                    html.substringAfter("alert('").substringBefore("')")
                } else {
                    doc.select("font[color=red]").text().trim()
                }

                runOnUiThread {
                    if (html.contains("成功") || html.contains("匹配") || html.contains("window.close")) {
                        // ✅ 成功：弹出成功提示并销毁输入框
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("验证通过")
                            .setMessage("核验成功！密码将通过短信发送，请查收。")
                            .setPositiveButton("好", null).show()
                        dialog.dismiss()
                    } else {
                        // ❌ 失败：显示原因，不销毁输入框
                        val finalReason = if (errorMsg.isBlank()) "信息核验未通过" else errorMsg

                        tvHint.text = "⚠️ 核验失败：$finalReason"
                        tvHint.setTextColor(Color.RED)

                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("核验未通过")
                            .setMessage("教务处反馈：$finalReason")
                            .setPositiveButton("返回修改", null)
                            .show()

                        btnConfirm.isEnabled = true
                        btnConfirm.text = "立即核验"
                    }
                }
            }
        })
    }

    // 🌟 1. 检查并申请通知权限
    private fun checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // 🌟 必须使用这个标准的检查方式
            val status = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            )
            if (status != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }

    // 🌟 2. 安全地发送通知
    // 🌟 1. 唯一的、安全的通知发送函数
    /**
     * 🌟 锁屏看板完整代码
     * @param courseName 课程名称
     * @param room 教室信息
     * @param color 动态多巴胺颜色（由课表计算得出）
     */
    // 🌟 核心修复：增加第三个参数 color: Int
    // 🌟 核心修复：函数头必须定义 3 个参数，才能接收传进来的 bgInt
    /**
     * 🌟 深度美化版：锁屏看板发送函数
     */
    // 🌟 核心修复：确保参数列表包含 status: String
    private fun showLockScreenCourse(courseName: String, room: String, color: Int, status: String) {
        createNotificationChannel()

        // 1. 权限检查
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }

        // 2. 加载布局并设置文字
        val remoteViews = RemoteViews(packageName, R.layout.notification_course)
        remoteViews.setTextViewText(R.id.notif_course_name, courseName)
        remoteViews.setTextViewText(R.id.notif_course_room, "📍 $room")
        remoteViews.setTextViewText(R.id.notif_status_tag, status)

        if (status == "即将开始") {
            remoteViews.setTextColor(R.id.notif_status_tag, android.graphics.Color.GRAY)
        } else {
            remoteViews.setTextColor(R.id.notif_status_tag, android.graphics.Color.parseColor("#2196F3"))
        }

        remoteViews.setInt(R.id.notif_color_block, "setBackgroundColor", color)
        remoteViews.setTextColor(R.id.notif_color_block, android.graphics.Color.WHITE)

        // 🌟 3. 新增：准备点击跳转的逻辑
        // Intent 指向当前的 MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            // 确保点击时不会重复打开多个 Activity 实例
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // 将 Intent 包装成 PendingIntent (由系统代为执行)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 4. 构建并发送通知
        val builder = androidx.core.app.NotificationCompat.Builder(this, "course_monitor_v99")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            //.setCustomContentView(remoteViews)
            //.setCustomBigContentView(remoteViews)
            .setContentTitle(courseName)
            .setContentText(room)
            .setOngoing(true)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent) // 🌟 核心修改：设置点击意图
            .setOnlyAlertOnce(true)


        try {
            val manager = androidx.core.app.NotificationManagerCompat.from(this)
            manager.notify(10086, builder.build())
            Log.d("Notif", "✅ 发送成功: $courseName ($status)")
        } catch (e: Exception) {
            Log.e("Notif", "❌ 发送失败: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // 🌟 核心：不再寻找 grid，直接调用纯逻辑函数刷新通知
        if (currentWeek != 0) {
            refreshNotificationOnly()
            Log.d("Notif", "🏠 回到前台，已触发实时通知检查")
        }
    }

    // 🌟 只要你的 App 彻底关闭，就停止“心跳”计时器
    override fun onDestroy() {
        super.onDestroy()
        // 🌟 只有当 Handler 已经初始化了，才去移除它，防止崩溃
        if (::tickHandler.isInitialized) {
            tickHandler.removeCallbacks(tickRunnable)
        }
    }

    // 🌟 专门用于刷新通知，不依赖 UI 布局
    private fun refreshNotificationOnly() {
        if (currentWeek == 0) return

        val now = Calendar.getInstance()
        val today = ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val currentTime = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val todayCourses = (fetchedList + manualList).filter {
            it.day == today && it.weekList.contains(currentWeek)
        }.sortedBy { it.startSection }

        if (todayCourses.isNotEmpty()) {
            // 1. 寻找“还没下课”的课
            val displayCourse = todayCourses.find {
                val endTime = when(it.endSection) {
                    1 -> 8*60+55; 2 -> 9*60+50; 3 -> 10*60+55; 4 -> 11*60+50; 5 -> 15*60+5;
                    6 -> 16*60+0; 7 -> 17*60+5; 8 -> 18*60+0; 9 -> 20*60+15; 10 -> 21*60+10
                    else -> 0
                }
                currentTime < endTime
            }

            // 🌟 核心修改：如果没有“还没下课”的课了，说明今天课上完了
            if (displayCourse == null) {
                // 方案 A：显示“今日课程已结束”
                showLockScreenCourse("今日课程已结束", "早点休息 🌙", android.graphics.Color.GRAY, "已完成")

                // 方案 B：如果你想让通知直接消失，取消上面的注释并改用下面这行：
                // androidx.core.app.NotificationManagerCompat.from(this).cancel(10086)
                return
            }

            // 2. 正常显示逻辑（进行中/即将开始）
            val startTime = when(displayCourse.startSection) {
                1 -> 8*60+10; 2 -> 9*60+5; 3 -> 10*60+10; 4 -> 11*60+5; 5 -> 14*60+20;
                6 -> 15*60+15; 7 -> 16*60+20; 8 -> 17*60+15; 9 -> 19*60+30; 10 -> 20*60+25
                else -> 0
            }
            val status = if (currentTime >= startTime) "进行中" else "即将开始"

            val seed = displayCourse.name.hashCode().toLong()
            val hue = (java.util.Random(seed).nextInt(360)).toFloat()
            val notifBgInt = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, 0.8f, 0.75f))
            val timeRange = "${getTimeRange(displayCourse.startSection).split(" - ")[0]} - ${getTimeRange(displayCourse.endSection).split(" - ")[1]}"

            showLockScreenCourse(displayCourse.name, "${displayCourse.room} | $timeRange", notifBgInt, status)
        } else {
            // 今天原本就没课的情况
            showLockScreenCourse("今日无课", "享受自由时光 ☕", android.graphics.Color.LTGRAY, "休假中")
        }
    }
}