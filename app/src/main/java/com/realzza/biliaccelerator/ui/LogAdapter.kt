package com.realzza.biliaccelerator.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.realzza.biliaccelerator.R
import com.realzza.biliaccelerator.core.RewriteLogEntry

class LogAdapter(private var logs: List<RewriteLogEntry>) : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    fun updateLogs(newLogs: List<RewriteLogEntry>) {
        this.logs = newLogs
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount(): Int = logs.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTag: TextView = itemView.findViewById(R.id.tv_tag)
        private val tvReason: TextView = itemView.findViewById(R.id.tv_reason)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvFromHost: TextView = itemView.findViewById(R.id.tv_from_host)
        private val tvToHost: TextView = itemView.findViewById(R.id.tv_to_host)

        fun bind(entry: RewriteLogEntry) {
            tvTime.text = entry.timeFormatted
            tvReason.text = entry.reason
            tvFromHost.text = entry.originalHost
            tvToHost.text = entry.targetHost

            when {
                entry.isPcdn -> {
                    tvTag.text = "PCDN"
                    tvTag.setTextColor(Color.parseColor("#E53935"))
                    tvTag.setBackgroundColor(Color.parseColor("#20E53935"))
                }
                entry.isMcdn -> {
                    tvTag.text = "MCDN"
                    tvTag.setTextColor(Color.parseColor("#FB8C00"))
                    tvTag.setBackgroundColor(Color.parseColor("#20FB8C00"))
                }
                entry.reason == "szbdyd-source" -> {
                    tvTag.text = "SCHED"
                    tvTag.setTextColor(Color.parseColor("#00AEEC"))
                    tvTag.setBackgroundColor(Color.parseColor("#2000AEEC"))
                }
                else -> {
                    tvTag.text = "UPOS"
                    tvTag.setTextColor(Color.parseColor("#43A047"))
                    tvTag.setBackgroundColor(Color.parseColor("#2043A047"))
                }
            }
        }
    }
}
