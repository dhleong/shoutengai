package net.dhleong.shoutengai

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import io.reactivex.disposables.CompositeDisposable

class MainActivity : AppCompatActivity() {

    val subs = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onStart() {
        super.onStart()

        subs.add(
            Shoutengai(this, "")
                .queryPurchases("hi")
                .toList()
                .subscribe { purchases ->
                    Log.v("shoutengai", "Found $purchases")
                }
        )
    }

    override fun onStop() {
        super.onStop()

        subs.clear()
    }
}
