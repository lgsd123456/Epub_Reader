package cn.com.lg.epubreader

import android.app.Application
import cn.com.lg.epubreader.data.database.AppDatabase

class EpubReaderApp : Application() {
    
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
    }
}
