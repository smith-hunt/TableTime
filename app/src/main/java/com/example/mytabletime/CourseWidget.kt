package com.example.mytabletime

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

class CourseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // 1. 从你定义的加密存储中读取课表
            val prefs = context.getSharedPreferences("normal_config", Context.MODE_PRIVATE)
            val json = prefs.getString("saved_kb", null)

            if (json != null) {
                val sType = object : TypeToken<List<Course>>() {}.type
                val list: List<Course> = Gson().fromJson(json, sType)

                // 2. 简单的寻找逻辑：找今天的下一节课
                val now = Calendar.getInstance()
                val today = ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // 转为你的 1-7

                val nextCourse = list.filter { it.day == today }.minByOrNull { it.startSection }

                if (nextCourse != null) {
                    views.setTextViewText(R.id.widget_course_name, nextCourse.name)
                    views.setTextViewText(R.id.widget_course_info, "📍 ${nextCourse.room} | 第${nextCourse.startSection}节")
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}