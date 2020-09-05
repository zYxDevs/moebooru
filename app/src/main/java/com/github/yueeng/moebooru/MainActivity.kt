package com.github.yueeng.moebooru

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.paging.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.github.yueeng.moebooru.databinding.FragmentListBinding
import com.github.yueeng.moebooru.databinding.FragmentMainBinding
import com.github.yueeng.moebooru.databinding.ImageItemBinding
import com.github.yueeng.moebooru.databinding.StateItemBinding
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import java.util.*

class ImageDataSource(private val query: Q? = Q()) : PagingSource<Int, JImageItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JImageItem> = try {
        val key = params.key ?: 1
        val posts = Service.instance.post(page = key, query!!.rating(Q.Rating.safe), limit = params.loadSize)
        LoadResult.Page(posts, null, if (posts.size == params.loadSize) key + 1 else null)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}

class ImageViewModel(handle: SavedStateHandle, defaultArgs: Bundle?) : ViewModel() {
    val posts = Pager(PagingConfig(20)) { ImageDataSource(defaultArgs?.getParcelable("query")) }.flow
}

class ImageViewModelFactory(owner: SavedStateRegistryOwner, private val defaultArgs: Bundle?) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = ImageViewModel(handle, defaultArgs) as T
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? MainFragment ?: MainFragment()
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class MainFragment : Fragment() {
    private val adapter by lazy { PagerAdapter(this) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentMainBinding.inflate(inflater, container, false).also { binding ->
            (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
            binding.pager.adapter = adapter
            TabLayoutMediator(binding.tab, binding.pager) { tab, position -> tab.text = adapter.data[position].first }.attach()
        }.root

    class PagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        val data = listOf(
            "Newest" to Q(),
            "Day" to Q().popular_by_day(Date()),
            "Week" to Q().popular_by_week(Date()),
            "Month" to Q().popular_by_month(Date())
        )

        override fun getItemCount(): Int = data.size

        override fun createFragment(position: Int): Fragment = ListFragment().apply {
            arguments = bundleOf("query" to data[position].second, "name" to data[position].first)
        }
    }
}

class ListFragment : Fragment() {
    private val adapter by lazy { ImageAdapter() }
    private val model: ImageViewModel by viewModels { ImageViewModelFactory(this, arguments) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentListBinding.inflate(inflater, container, false).also { binding ->
            binding.recycler.adapter = adapter.withLoadStateFooter(HeaderAdapter(adapter))
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow.collectLatest {
                    binding.swipe.isRefreshing = it.refresh is LoadState.Loading
                }
            }
            lifecycleScope.launchWhenCreated {
                adapter.loadStateFlow
                    // Only emit when REFRESH LoadState for RemoteMediator changes.
                    .distinctUntilChangedBy { it.refresh }
                    // Only react to cases where Remote REFRESH completes i.e., NotLoading.
                    .filter { it.refresh is LoadState.NotLoading }
                    .collect { binding.recycler.scrollToPosition(0) }
            }
            binding.swipe.setOnRefreshListener { adapter.refresh() }
            lifecycleScope.launchWhenCreated {
                model.posts.collectLatest { adapter.submitData(it) }
            }
        }.root

    class ImageHolder(private val binding: ImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: JImageItem) {
            bindImageRatio(binding.image1, item.sample_width, item.sample_height)
            bindImageFromUrl(binding.image1, item.sample_url, binding.progress, R.mipmap.ic_launcher)
        }
    }

    class ImageAdapter : PagingDataAdapter<JImageItem, ImageHolder>(diff) {
        override fun onBindViewHolder(holder: ImageHolder, position: Int) {
            holder.bind(getItem(position)!!)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder =
            ImageHolder(ImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        companion object {
            val diff = object : DiffUtil.ItemCallback<JImageItem>() {
                override fun areItemsTheSame(oldItem: JImageItem, newItem: JImageItem): Boolean = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: JImageItem, newItem: JImageItem): Boolean = oldItem == newItem
            }
        }
    }

    class HeaderHolder(parent: ViewGroup, private val retryCallback: () -> Unit) :
        RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.state_item, parent, false)) {

        private val binding = StateItemBinding.bind(itemView)
        private val progressBar = binding.progressBar
        private val errorMsg = binding.errorMsg
        private val retry = binding.retryButton.also { it.setOnClickListener { retryCallback() } }

        fun bindTo(loadState: LoadState) {
            progressBar.isVisible = loadState is LoadState.Loading
            retry.isVisible = loadState is LoadState.Error
            when (loadState) {
                is LoadState.Error -> {
                    errorMsg.isVisible = true
                    errorMsg.text = loadState.error.message
                }
                is LoadState.NotLoading -> {
                    errorMsg.isVisible = loadState.endOfPaginationReached
                    errorMsg.text = if (loadState.endOfPaginationReached) "END" else null
                }
                else -> {
                    errorMsg.isVisible = false
                    errorMsg.text = null
                }
            }
        }
    }

    class HeaderAdapter(private val adapter: ImageAdapter) : LoadStateAdapter<HeaderHolder>() {
        override fun displayLoadStateAsItem(loadState: LoadState): Boolean = when (loadState) {
            is LoadState.NotLoading -> loadState.endOfPaginationReached
            else -> super.displayLoadStateAsItem(loadState)
        }

        override fun onBindViewHolder(holder: HeaderHolder, loadState: LoadState) = holder.bindTo(loadState)

        override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): HeaderHolder = HeaderHolder(parent) { adapter.retry() }
    }
}