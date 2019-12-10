package com.e.androidwebrtc_sample_kt

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.e.androidwebrtc_sample_kt.databinding.OnlineUsrsItemBinding

class OnlineUserAdapter(
    private val data: List<OnlineUser>,
    private val onCall: (id: String) -> Unit
) :
    RecyclerView.Adapter<OnlineUserAdapter.OuViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OuViewHolder {
        return OuViewHolder(
            OnlineUsrsItemBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: OuViewHolder, position: Int) {
        holder.onBind(data[position], onCall)
    }

    class OuViewHolder(private val binding: OnlineUsrsItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(
            user: OnlineUser,
            onCall: (id: String) -> Unit
        ) {
            binding.data = user
            binding.callButton.setOnClickListener { onCall.invoke(user.id) }
        }
    }
}
