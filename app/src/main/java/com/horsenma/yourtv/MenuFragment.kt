package com.horsenma.yourtv


import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.horsenma.yourtv.databinding.MenuBinding
import com.horsenma.yourtv.models.TVListModel
import com.horsenma.yourtv.models.TVModel

class MenuFragment : Fragment(), GroupAdapter.ItemListener, ListAdapter.ItemListener {
    private var _binding: MenuBinding? = null
    private val binding get() = _binding!!

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var listAdapter: ListAdapter

    private var groupWidth = 0
    private var listWidth = 0

    private lateinit var viewModel: MainViewModel
    private var isInitialFocusSet = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = MenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireActivity()
        val application = context.applicationContext as YourTVApplication
        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        groupAdapter = GroupAdapter(
            context,
            binding.group,
            viewModel.groupModel,
        )
        binding.group.adapter = groupAdapter
        binding.group.layoutManager =
            LinearLayoutManager(context)
        groupWidth = application.px2Px(binding.group.layoutParams.width)
        binding.group.layoutParams.width = if (SP.compactMenu) {
            groupWidth * 2 / 3
        } else {
            groupWidth
        }
        groupAdapter.setItemListener(this)

        listAdapter = ListAdapter(
            context,
            binding.list,
            getList(),
        )
        binding.list.adapter = listAdapter
        binding.list.layoutManager =
            LinearLayoutManager(context)
        listWidth = application.px2Px(binding.list.layoutParams.width)
        binding.list.layoutParams.width = if (SP.compactMenu) {
            listWidth * 4 / 5
        } else {
            listWidth
        }
        listAdapter.setItemListener(this)

        binding.menu.setOnClickListener {
            hideSelf()
        }

//        groupAdapter.focusable(false)

        groupAdapter.focusable(true)
        listAdapter.focusable(true)

        onVisible()
        ensureFocus()

        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                val mainActivity = activity as? MainActivity
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        mainActivity?.menuActive()
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        mainActivity?.menuActive()
                    }
                }
            }
        }
        binding.group.addOnScrollListener(scrollListener)
        binding.list.addOnScrollListener(scrollListener)

    }

    private fun getList(): TVListModel? {
        if (!this::viewModel.isInitialized) {
            return null
        }

        // 如果不存在當前組，則切換到收藏組
        if (viewModel.groupModel.getCurrentList() == null) {
            viewModel.groupModel.setPosition(0)
        }

        return viewModel.groupModel.getCurrentList()
    }

    // 替换 update 方法
    fun update() {
        view?.post {
            groupAdapter.changed()
            getList()?.let {
                (binding.list.adapter as ListAdapter).update(it)
            }
        }
    }

    fun updateSize() {
        view?.post {
            binding.group.layoutParams.width = if (SP.compactMenu) {
                groupWidth * 2 / 3
            } else {
                groupWidth
            }

            binding.list.layoutParams.width = if (SP.compactMenu) {
                listWidth * 4 / 5
            } else {
                listWidth
            }
        }
    }

    fun updateList(position: Int) {
        if (!this::viewModel.isInitialized) {
            return
        }

        viewModel.groupModel.setPosition(position)
        SP.positionGroup = position

        viewModel.groupModel.getCurrentList()?.let {
            (binding.list.adapter as ListAdapter).update(it)
        }
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
    }

    override fun onItemFocusChange(listTVModel: TVListModel, hasFocus: Boolean) {
        if (hasFocus) {
            (binding.list.adapter as ListAdapter).update(listTVModel)
    //        (activity as MainActivity).menuActive()
        }
    }

    override fun onItemFocusChange(tvModel: TVModel, hasFocus: Boolean) {
        if (hasFocus) {
    //        (activity as MainActivity).menuActive()
        }
    }

    override fun onItemClicked(position: Int) {
        if (!this::viewModel.isInitialized) {
            return
        }
    }

    override fun onItemClicked(position: Int, type: String) {
        if (!this::viewModel.isInitialized) {
            return
        }

        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.let {
            it.setPosition(position)
            it.setPositionPlaying()
            it.getCurrent()?.setReady()
        }

        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
    }

    override fun onKey(keyCode: Int): Boolean {
        val mainActivity = activity as? MainActivity
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                mainActivity?.menuActive()
                return false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (listAdapter.itemCount == 0) {
                    return true
                }
                listAdapter.focusable(true)
                groupAdapter.focusable(false)
                binding.list.requestFocus()
                listAdapter.toPosition(viewModel.groupModel.getCurrentList()?.positionPlayingValue ?: 0)
                mainActivity?.menuActive()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                groupAdapter.focusable(true)
                listAdapter.focusable(false)
                binding.group.requestFocus()
                groupAdapter.scrollToPositionAndSelect(viewModel.groupModel.positionValue)
                mainActivity?.menuActive()
                return true
            }
        }
        return false
    }

    override fun onKey(listAdapter: ListAdapter, keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                groupAdapter.focusable(true)
                listAdapter.focusable(false)
                binding.group.requestFocus()
                groupAdapter.scrollToPositionAndSelect(viewModel.groupModel.positionValue)
                (activity as? MainActivity)?.menuActive()
                return true
            }
        }
        return false
    }

    fun onVisible() {
        if (viewModel.groupModel.tvGroupValue.size < 2 || viewModel.groupModel.getAllList()
                ?.size() == 0
        ) {
            // R.string.channel_not_exist.showToast()
            return
        }

        val position = viewModel.groupModel.positionPlayingValue
        if (position != viewModel.groupModel.positionValue
        ) {
            updateList(position)
        }
        viewModel.groupModel.getCurrentList()?.let {
            listAdapter.toPosition(it.positionPlayingValue)
        }

        (activity as MainActivity).menuActive()
    }

    // 替换 onHiddenChanged 方法
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            onVisible()
            ensureFocus() // 确保显示时焦点恢复
        } else {
            view?.post {
                groupAdapter.visible = false
                listAdapter.visible = false
            }
        }
    }

    private fun ensureFocus() {
        if (isInitialFocusSet) return // 仅初次设置焦点
        view?.postDelayed({
            val groupRecyclerView = binding.group
            groupRecyclerView.isFocusable = true
            groupRecyclerView.isFocusableInTouchMode = true
            val recyclerView = binding.list
            if (!groupRecyclerView.hasFocus() && groupAdapter.itemCount > 0) {
                groupAdapter.focusable(true)
                listAdapter.focusable(false)
                groupRecyclerView.requestFocus()
                groupAdapter.scrollToPositionAndSelect(viewModel.groupModel.positionValue)
                groupRecyclerView.findViewHolderForAdapterPosition(viewModel.groupModel.positionValue)?.itemView?.requestFocus()
                Log.d(TAG, "MenuFragment: Forced focus on group RecyclerView")
            } else if (!recyclerView.hasFocus() && listAdapter.itemCount > 0) {
                listAdapter.focusable(true)
                groupAdapter.focusable(false)
                recyclerView.requestFocus()
                val position = viewModel.groupModel.getCurrentList()?.positionPlayingValue ?: 0
                listAdapter.toPosition(position)
                recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                Log.d(TAG, "MenuFragment: Forced focus on list RecyclerView")
            }
            isInitialFocusSet = true // 标记焦点已设置
        }, 200)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "MenuFragment"
    }
}