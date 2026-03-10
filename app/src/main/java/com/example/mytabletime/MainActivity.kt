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
import android.graphics.drawable.Drawable
import android.graphics.drawable.ClipDrawable
import android.content.res.ColorStateList
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.content.SharedPreferences


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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        // 绑定底部 5 个 Tab 按钮
        findViewById<View>(R.id.tab_kb_btn).setOnClickListener { switchTab(0) }
        findViewById<View>(R.id.tab_exam_btn).setOnClickListener { switchTab(1) }
        findViewById<View>(R.id.tab_grade_btn).setOnClickListener { switchTab(2) }
        findViewById<View>(R.id.tab_credit_btn).setOnClickListener { switchTab(3) } // 🌟 新增
        findViewById<View>(R.id.tab_account_btn).setOnClickListener { switchTab(4) } // 变成 4

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

    private fun fetchCreditOrPlanData(targetUrl: String, loadingMsg: String) {
        val tvContent = findViewById<TextView>(R.id.tv_credit_content)
        tvContent.text = loadingMsg
        updateStatus(loadingMsg)

        val request = Request.Builder()
            .url(targetUrl)
            // 教务处通常需要一个主页的 Referer 防盗链
            .addHeader("Referer", "https://jiaowu.sicau.edu.cn/jiaoshi/bangong/index1.asp")
            .build()

        myClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { tvContent.text = "网络连接失败，请检查是否已连接校园网或VPN" }
            }

            override fun onResponse(call: Call, response: Response) {
                val html = decodeGb2312(response)
                val doc = Jsoup.parse(html)

                // 寻找页面里最大的表格（教务处的排版一般核心数据都在最后一个 table 里）
                val tables = doc.select("table")
                if (tables.isEmpty()) {
                    runOnUiThread { tvContent.text = "解析失败：未找到数据表格，可能登录已过期" }
                    return
                }

                val targetTable = tables.last()
                val rows = targetTable?.select("tr") ?: return

                val sb = StringBuilder()
                for (row in rows) {
                    val cols = row.select("td, th") // 提取表格的单元格或表头
                    val rowText = cols.joinToString("  |  ") { it.text().trim() }

                    if (rowText.isNotBlank()) {
                        sb.append(rowText).append("\n")
                        sb.append("--------------------------------------------------\n")
                    }
                }

                runOnUiThread {
                    if (sb.isEmpty()) {
                        tvContent.text = "暂无数据"
                    } else {
                        tvContent.text = sb.toString()
                        updateStatus("数据拉取成功")
                    }
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

        for (g in grades) {
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

            // 🌟 核心：使用 RelativeLayout 确保分数位置绝对固定
            val topRow = RelativeLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }

            // 1. 右侧分数：先定义它，给它预留空间
            val tvScore = TextView(this).apply {
                id = View.generateViewId()
                text = g.score
                textSize = 20f
                // 🌟 分数换颜色：这里换成了更有活力的“深青色”，与绩点区分
                setTextColor(Color.parseColor("#00897B"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                val p = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                p.addRule(RelativeLayout.ALIGN_PARENT_END)
                p.addRule(RelativeLayout.CENTER_VERTICAL)
                layoutParams = p
            }

            // 2. 左侧容器：包含 名称 + 绩点
            val leftContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                p.addRule(RelativeLayout.ALIGN_PARENT_START)
                p.addRule(RelativeLayout.START_OF, tvScore.id) // 🌟 绝对不会挡住分数
                p.setMarginEnd(20) // 留点呼吸间距
                layoutParams = p
            }

            val tvName = TextView(this).apply {
                text = g.name
                textSize = 15f
                setTextColor(Color.parseColor("#333333"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setSingleLine(true)
                // 🌟 关键：权重设为 1，名称长了会自动打省略号，保护绩点和分数
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }

            val tvGpa = TextView(this).apply {
                text = " [绩点 ${g.gpa}]"
                textSize = 13f
                // 🌟 绩点用蓝色
                setTextColor(Color.parseColor("#2196F3"))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }

            leftContainer.addView(tvName)
            leftContainer.addView(tvGpa)

            topRow.addView(tvScore)
            topRow.addView(leftContainer)

            // 底部详情栏
            val tvDetail = TextView(this).apply {
                this.text = "学分: ${g.credit}  |  ${g.nature}"
                this.setTextSize(12f)
                this.setTextColor(Color.parseColor("#999999"))
                setPadding(0, 10, 0, 0)
            }

            card.addView(topRow)
            card.addView(tvDetail)
            container.addView(card)
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

        // 🌟 核心修复 1：显示数据时，把那个“请选择项目”的占位文字彻底隐藏，消除空白
        findViewById<TextView>(R.id.tv_credit_content)?.let { it.visibility = View.GONE }

        for (cat in categories) {
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

            // 🌟 核心修复 2：使用 RelativeLayout 锚点逻辑防止遮挡
            val titleRow = RelativeLayout(this).apply {
                setLayoutParams(LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }

            // 1. 先定义百分比文本（靠右对齐）
            val tvPercent = TextView(this).apply {
                id = View.generateViewId() // 🌟 生成唯一 ID
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

            // 2. 名称文本（靠左对齐，但截止到百分比之前）
            val tvName = TextView(this).apply {
                setText(cat.name)
                setTextSize(15f)
                setTextColor(Color.parseColor("#1A237E"))
                setTypeface(null, android.graphics.Typeface.BOLD)

                // 🌟 关键：防止溢出
                setSingleLine(true)
                setEllipsize(android.text.TextUtils.TruncateAt.END)

                val p = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                p.addRule(RelativeLayout.ALIGN_PARENT_START)
                p.addRule(RelativeLayout.START_OF, tvPercent.id) // 🌟 绝对不准越过百分比
                p.setMarginEnd(20)
                setLayoutParams(p)
            }

            titleRow.addView(tvPercent)
            titleRow.addView(tvName)

            // 进度条和详情（代码保持不变...）
            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                setLayoutParams(LinearLayout.LayoutParams(MATCH_PARENT, 16).apply { setMargins(0, 24, 0, 24) })
                setMax(100)
                setProgress(cat.progress.toDoubleOrNull()?.toInt() ?: 0)
                setProgressTintList(ColorStateList.valueOf(tvPercent.currentTextColor))
                setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#ECEFF1")))
            }

            val tvData = TextView(this).apply {
                // 1. 获取原始 gap 并乘以 -1
                val originalGap = cat.gap.toDoubleOrNull() ?: 0.0
                val flippedGap = originalGap * -1

                // 2. 根据正负值决定显示的文字内容
                val gapStatusText = when {
                    flippedGap < 0 -> "已超修 ${-flippedGap} 分" // 🌟 如果是负数，取绝对值显示为“超修”
                    flippedGap > 0 -> "还差 $flippedGap 分"      // 🌟 如果是正数，显示为“还差”
                    else -> "已修满"                             // 🌟 如果是 0
                }

                // 3. 设置显示文字并根据状态切换颜色 (可选)
                setText("已修 ${cat.earned} / 应修 ${cat.required} ($gapStatusText)")
                setTextSize(12f)

                // 🌟 进阶：如果是超修或修满，颜色变绿；如果还差分，保持灰色或变红
                if (flippedGap <= 0) {
                    setTextColor(Color.parseColor("#4CAF50")) // 绿色
                } else {
                    setTextColor(Color.parseColor("#90A4AE")) // 默认灰
                }
            }

            card.addView(titleRow)
            card.addView(progressBar)
            card.addView(tvData)
            container.addView(card)
        }
    }

    // 一个简单的辅助函数，让进度条颜色更智能
    private fun createProgressDrawable(progress: Double): Drawable {
        val color = when {
            progress >= 100.0 -> "#4CAF50" // 绿色
            progress >= 60.0 -> "#2196F3"  // 蓝色
            else -> "#FF9800"             // 橙色
        }
        val pgDrawable = GradientDrawable().apply {
            cornerRadius = 10f
            setColor(Color.parseColor(color))
        }
        val bgDrawable = GradientDrawable().apply {
            cornerRadius = 10f
            setColor(Color.parseColor("#E0E0E0"))
        }
        return ClipDrawable(pgDrawable, Gravity.START, ClipDrawable.HORIZONTAL).apply {
            // 这里逻辑较复杂，建议简单使用颜色或 LayerDrawable
        }
        // 简单起见，可以直接用系统的颜色过滤
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
        // 1. 确定是编辑还是添加
        val isEdit = c != null
        // 如果是编辑，使用传入的课程；如果是添加，新建一个默认25周全选的课程
        val target = c ?: Course("", "", "", d, s, e, (1..25).toMutableList())

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(50, 30, 50, 0)

        // 2. 显示时间详情（不论是加课还是改课，让用户看清楚时间）
        val tvTimeInfo = TextView(this)
        tvTimeInfo.text = "节次: 周${target.day} 第${target.startSection}-${target.endSection}节"
        tvTimeInfo.textSize = 14f
        tvTimeInfo.setTextColor(Color.GRAY)
        tvTimeInfo.setPadding(0, 0, 0, 20)
        root.addView(tvTimeInfo)

        // 3. 基础信息输入框
        val nE = EditText(this)
        nE.hint = "课程名"
        nE.setText(target.name)
        root.addView(nE)

        val rE = EditText(this)
        rE.hint = "教室"
        rE.setText(target.room)
        root.addView(rE)

        val tE = EditText(this)
        tE.hint = "老师"
        tE.setText(target.teacher)
        root.addView(tE)

        // 4. 快捷按钮行 (全选/单/双)
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.setPadding(0, 20, 0, 10)

        val bAll = Button(this, null, android.R.attr.buttonStyleSmall)
        bAll.text = "全选"
        btnRow.addView(bAll)

        val bOdd = Button(this, null, android.R.attr.buttonStyleSmall)
        bOdd.text = "单周"
        btnRow.addView(bOdd)

        val bEven = Button(this, null, android.R.attr.buttonStyleSmall)
        bEven.text = "双周"
        btnRow.addView(bEven)

        root.addView(btnRow)

        // 5. 25周方块网格
        val weekGrid = GridLayout(this)
        weekGrid.columnCount = 5

        val boxes = mutableListOf<CheckBox>()
        for (i in 1..25) {
            val cb = CheckBox(this)
            cb.text = "$i"
            cb.isChecked = target.weekList.contains(i)
            cb.setButtonDrawable(0) // 去掉默认的打勾框
            cb.gravity = Gravity.CENTER

            // 💡 1. 删掉了原来的 setBackgroundResource(android.R.drawable.btn_default)

            val glp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            glp.width = 0
            glp.height = 100
            glp.setMargins(4, 4, 4, 4)
            cb.layoutParams = glp

            // 💡 2. 专门写一个刷新背景的逻辑（带点圆角和边框，更好看）
            val updateBackground = { button: View, isChecked: Boolean ->
                val shape = GradientDrawable()
                shape.cornerRadius = 8f // 一点小圆角
                if (isChecked) {
                    shape.setColor(Color.parseColor("#E3F2FD")) // 选中：浅蓝色背景
                    shape.setStroke(0, Color.TRANSPARENT)
                } else {
                    shape.setColor(Color.TRANSPARENT) // 未选：透明（透出白色底）
                    shape.setStroke(2, Color.parseColor("#E0E0E0")) // 未选：加上浅灰色边框，像个正规的框
                }
                button.background = shape
            }

            // 💡 3. 初始化时强制刷一遍背景，确保一进来就是对的颜色
            updateBackground(cb, cb.isChecked)

            // 💡 4. 点击时再刷一遍
            cb.setOnCheckedChangeListener { button, checked ->
                updateBackground(button, checked)
            }

            boxes.add(cb)
            weekGrid.addView(cb)
        }

        // 快捷键逻辑
        bAll.setOnClickListener { boxes.forEach { it.isChecked = true } }
        bOdd.setOnClickListener { boxes.forEachIndexed { i, b -> b.isChecked = (i + 1) % 2 != 0 } }
        bEven.setOnClickListener { boxes.forEachIndexed { i, b -> b.isChecked = (i + 1) % 2 == 0 } }

        // 6. 滚动区域
        val scroll = ScrollView(this)
        scroll.addView(weekGrid)
        val slp = LinearLayout.LayoutParams(MATCH_PARENT, 500)
        scroll.layoutParams = slp
        root.addView(scroll)

        // 7. 弹窗保存
        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "编辑课程" else "添加课程")
            .setView(root)
            .setPositiveButton("保存") { _, _ ->
                target.name = nE.text.toString()
                target.room = rE.text.toString()
                target.teacher = tE.text.toString()
                target.weekList.clear()
                boxes.forEachIndexed { i, b -> if (b.isChecked) target.weekList.add(i + 1) }

                // 如果是新课，才加入列表；如果是老课，直接修改引用的内容即可
                if (!isEdit) manualList.add(target)

                saveCoursesToLocal(fetchedList + manualList)
                selDay = -1
                viewPager.adapter?.notifyDataSetChanged()
            }
            .setNegativeButton("取消", null)
            .show()
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

                // 打印调试信息，确保逻辑走到这里
                android.util.Log.d("DEBUG_DATE", "渲染 $dStr 到第 $i 列")

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
                        setStroke(4, Color.parseColor("#2196F3"))
                        setColor(Color.parseColor("#E3F2FD"))
                        setCornerRadius(12f)
                    })
                }
            }
            grid.addView(box)
        }

        // 2. 侧边 1-10 节次
        for (i in 1..10) {
            val tv = TextView(this)
            tv.text = "$i"
            tv.gravity = Gravity.CENTER
            val lp = GridLayout.LayoutParams(GridLayout.spec(i), GridLayout.spec(0))
            lp.width = labelW
            lp.height = cellH
            tv.layoutParams = lp
            grid.addView(tv)
        }

        // 3. 占用矩阵与画课
        val occupied = Array(11) { BooleanArray(8) }
        val colorPairs = arrayOf(Pair("#E8F5E9", "#2E7D32"), Pair("#E3F2FD", "#1565C0"), Pair("#FFF3E0", "#EF6C00"), Pair("#F3E5F5", "#7B1FA2"), Pair("#FCE4EC", "#C2185B"))
        val all = fetchedList + manualList
        for (c in all) {
            if (!c.weekList.contains(weekIdx) || c.startSection > 10 || c.day > 7) continue
            for (r in c.startSection..c.endSection) { if (r <= 10) occupied[r][c.day] = true }
            val card = TextView(this)
            val sn = if (c.name.length > 8) c.name.take(7) + ".." else c.name
            card.text = "${sn}\n@${c.room}"
            val pair = colorPairs[Math.abs(c.name.hashCode()) % colorPairs.size]
            card.setTextColor(Color.parseColor(pair.second))
            card.textSize = 11f
            card.setTypeface(null, android.graphics.Typeface.BOLD)
            card.gravity = Gravity.CENTER
            card.setPadding(4, 4, 4, 4)
            val shape = GradientDrawable()
            shape.cornerRadius = 12f
            shape.setColor(Color.parseColor(pair.first))
            card.background = shape
            card.setOnClickListener {
                selDay = -1
                grid.post { renderSingleWeek(grid, weekIdx) }
                showCourseDetailDialog(c)
            }
            val span = minOf(c.endSection, 10) - c.startSection + 1
            val lp = GridLayout.LayoutParams(GridLayout.spec(c.startSection, span, GridLayout.FILL), GridLayout.spec(c.day, 1, GridLayout.FILL))
            lp.width = cellW - 6
            lp.height = cellH * span - 6
            lp.setMargins(3, 3, 3, 3)
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
                grid.addView(sensor, GridLayout.LayoutParams(GridLayout.spec(r), GridLayout.spec(cl)).apply { width = cellW; height = cellH })
            }
        }
    }
    // 💡 辅助：详情弹窗（带右上角编辑按钮）
    private fun showCourseDetailDialog(c: Course) {
        // 1. 创建自定义标题容器 (改用 LinearLayout 彻底解决溢出问题)
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL // 让里面的东西垂直居中对齐
            setPadding(50, 40, 50, 0)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 2. 创建标题文字（课程名）
        val tvTitle = TextView(this).apply {
            text = c.name
            textSize = 18f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)

            // 限制最多两行，多出来的用...表示
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END

            // 💡 核心魔法：宽度设为 0，权重设为 1f
            // 这样它会老老实实地占满剩下的空间，绝对不会把弹窗撑爆
            val titleParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            titleParams.setMargins(0, 0, 30, 0) // 给右边的编辑按钮留点呼吸空间
            layoutParams = titleParams
        }
        titleLayout.addView(tvTitle)

        // 3. 创建右上角“编辑”按钮
        val btnEdit = TextView(this).apply {
            text = "编辑"
            setTextColor(Color.parseColor("#2196F3"))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(20, 10, 20, 10)

            // 按钮就正常包裹自己的内容，不需要权重
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        titleLayout.addView(btnEdit)
        // 4. 构建对话框内容
        val msg = "教师: ${c.teacher}\n" +
                "教室: ${c.room}\n" +
                "节次: 周${c.day} 第${c.startSection}-${c.endSection}节"

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(titleLayout)
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .setNeutralButton("删除") { _, _ ->
                fetchedList.remove(c)
                manualList.remove(c)
                saveCoursesToLocal(fetchedList + manualList)
                viewPager.adapter?.notifyDataSetChanged()
            }
            .create()

        // 5. 绑定编辑点击事件
        btnEdit.setOnClickListener {
            dialog.dismiss()
            showCourseEditDialog(c) // 跳转到 25 周编辑页
        }

        dialog.show()
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

        // 将两个时间都重置到当天的凌晨 0 点，避免小时数干扰计算
        now.set(Calendar.HOUR_OF_DAY, 0)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)

        val diffMillis = now.timeInMillis - start.timeInMillis
        val diffDays = diffMillis / (1000 * 60 * 60 * 24)

        // 关键计算公式
        currentWeek = (diffDays / 7).toInt() + 1

        if (currentWeek < 1) currentWeek = 1
        runOnUiThread { updateStatus("✅ 当前周：第 $currentWeek 周") }
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        android.app.DatePickerDialog(this, { _, y, m, d ->
            val start = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }
            semesterStartMillis = start.timeInMillis
            getEncryptedPrefs().edit().putLong("start_date", semesterStartMillis).apply()
            calculateCurrentWeek()
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
            runOnUiThread { viewPager.adapter?.notifyDataSetChanged(); updateStatus("✅ 已载入离线课表")}
        } catch (e: Exception) {
            // 💡 如果格式对不上，直接清空，防止闪退
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

        // 🌟 修复 1：彻底移除占位文字，消灭顶部那一大段空白
        findViewById<TextView>(R.id.tv_credit_content)?.let {
            it.setVisibility(View.GONE)
        }

        for (p in plans) {
            val card = LinearLayout(this).apply {
                setOrientation(LinearLayout.VERTICAL)
                setPadding(40, 35, 40, 35)
                val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                lp.setMargins(0, 0, 0, 30) // 卡片间距
                setLayoutParams(lp)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setCornerRadius(30f)
                }
                setElevation(4f)
            }

            // --- 第一行：使用 RelativeLayout 解决名称遮挡 ---
            val topRow = RelativeLayout(this).apply {
                setLayoutParams(LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }

            // 1. 学分标签：作为右侧锚点，先定义它
            val tvCredit = TextView(this).apply {
                setId(View.generateViewId()) // 生成动态 ID
                setText("(${p.credit}学分)")
                setTextSize(14f)
                setTextColor(Color.parseColor("#2196F3"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                val lp = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                lp.addRule(RelativeLayout.ALIGN_PARENT_END) // 靠右对齐
                lp.addRule(RelativeLayout.CENTER_VERTICAL)
                setLayoutParams(lp)
            }

            // 2. 课程名称：受学分标签约束，绝对不会遮挡
            val tvName = TextView(this).apply {
                setText(p.name)
                setTextSize(16f)
                setTextColor(Color.parseColor("#222222"))
                setTypeface(null, android.graphics.Typeface.BOLD)

                // 🌟 核心：单行显示，过长则在学分前打断（...）
                setSingleLine(true)
                setEllipsize(android.text.TextUtils.TruncateAt.END)

                val lp = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                lp.addRule(RelativeLayout.ALIGN_PARENT_START) // 靠左
                lp.addRule(RelativeLayout.START_OF, tvCredit.getId()) // 🌟 关键：界限设在学分标签左边
                lp.setMarginEnd(20) // 留点呼吸间距
                setLayoutParams(lp)
            }

            topRow.addView(tvCredit)
            topRow.addView(tvName)

            // --- 第二行：英文名称 ---
            val tvEnName = TextView(this).apply {
                val enText = if (p.enName.isEmpty()) "无英文名" else p.enName
                setText(enText)
                setTextSize(12f)
                setTextColor(Color.parseColor("#999999"))
                setSingleLine(true)
                setEllipsize(android.text.TextUtils.TruncateAt.END)
                setPadding(0, 8, 0, 16)
            }

            // --- 第三行：底部详情栏 ---
            val bottomRow = RelativeLayout(this).apply {
                setLayoutParams(LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }

            val tvInfo = TextView(this).apply {
                setText("编号:${p.id} | 性质:${p.nature} | 第${p.term}学期")
                setTextSize(12f)
                setTextColor(Color.parseColor("#777777"))
                val lp = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                lp.addRule(RelativeLayout.ALIGN_PARENT_START)
                setLayoutParams(lp)
            }

            val btnDetail = TextView(this).apply {
                setText("详细 >")
                setTextSize(13f)
                setTextColor(Color.parseColor("#2196F3"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                val lp = RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                lp.addRule(RelativeLayout.ALIGN_PARENT_END)
                setLayoutParams(lp)
            }

            bottomRow.addView(tvInfo)
            bottomRow.addView(btnDetail)

            // 装配
            card.addView(topRow)
            card.addView(tvEnName)
            card.addView(bottomRow)

            // 点击逻辑
            val listener = View.OnClickListener { showPlanDetailDialog(p) }
            card.setOnClickListener(listener)
            btnDetail.setOnClickListener(listener)

            container.addView(card)
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
}