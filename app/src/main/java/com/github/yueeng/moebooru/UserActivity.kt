package com.github.yueeng.moebooru

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.yueeng.moebooru.databinding.FragmentUserBinding
import com.github.yueeng.moebooru.databinding.UserImageItemBinding
import com.github.yueeng.moebooru.databinding.UserTagItemBinding
import com.github.yueeng.moebooru.databinding.UserTitleItemBinding
import com.google.android.flexbox.*
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.Request
import org.jsoup.Jsoup


class UserActivity : MoeActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = supportFragmentManager.findFragmentById(R.id.container) as? UserFragment
            ?: UserFragment().apply { arguments = intent.extras }
        supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }
}

class UserViewModel(handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    val name = handle.getLiveData("name", args?.getString("name"))
    val user = handle.getLiveData("user", args?.getInt("user"))
    val avatar = handle.getLiveData<Int>("avatar")
    val background = handle.getLiveData<String>("background")
    val busy = handle.getLiveData("busy", false)
    val data = handle.getLiveData<Array<Parcelable>>("data")
}

class UserViewModelFactory(owner: SavedStateRegistryOwner, private val args: Bundle?) : AbstractSavedStateViewModelFactory(owner, args) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = UserViewModel(handle, args) as T
}

class UserFragment : Fragment() {
    private val mine by lazy { arguments?.containsKey("name") != true }
    private val model: UserViewModel by sharedViewModels({ arguments?.getString("name") ?: "" }) { UserViewModelFactory(this, arguments) }
    private val adapter by lazy { ImageAdapter() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(!mine)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.user, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.userLogin).isVisible = OAuth.name.value?.isEmpty() ?: true
        menu.findItem(R.id.userLogout).isVisible = OAuth.name.value?.isNotEmpty() ?: false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.login -> true.also { OAuth.login(this) }
        R.id.register -> true.also { OAuth.register(this) }
        R.id.reset -> true.also { OAuth.reset(this) }
        R.id.logout -> true.also { OAuth.logout(this) }
        R.id.changeEmail -> true.also { OAuth.changeEmail(this) }
        R.id.changePwd -> true.also { OAuth.changePwd(this) }
        else -> super.onOptionsItemSelected(item)
    }

    @FlowPreview
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        FragmentUserBinding.inflate(inflater, container, false).also { binding ->
            if (!mine) (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar) else {
                binding.toolbar.title = requireActivity().title
                binding.toolbar.setOnMenuItemClickListener { onOptionsItemSelected(it) }
            }
            lifecycleScope.launchWhenCreated {
                OAuth.name.asFlow().collectLatest {
                    if (mine) onPrepareOptionsMenu(binding.toolbar.menu) else requireActivity().invalidateOptionsMenu()
                    if (mine && it.isNotEmpty()) model.name.postValue(it)
                }
            }
            lifecycleScope.launchWhenCreated {
                OAuth.user.asFlow().collectLatest {
                    if (mine && it != 0) model.user.postValue(it)
                }
            }
            lifecycleScope.launchWhenCreated {
                model.name.asFlow().mapNotNull { it }.collectLatest { name ->
                    if (mine) binding.toolbar.title = name.toTitleCase()
                    else requireActivity().title = name.toTitleCase()
                    if (model.user.value == null) {
                        val id = Service.instance.user(name)
                        id.firstOrNull()?.id?.let { model.user.postValue(it) }
                    }
                }
            }
            lifecycleScope.launchWhenCreated {
                val flowUser = model.user.asFlow().mapNotNull { it }
                val flowTime = OAuth.timestamp.asFlow().drop(1)
                flowOf(flowUser, flowTime).flattenMerge(2).collectLatest {
                    if (model.user.value == null) return@collectLatest
                    GlideApp.with(binding.toolbar)
                        .load(OAuth.face(model.user.value!!))
                        .placeholder(R.mipmap.ic_launcher_foreground)
                        .override(120, 120)
                        .circleCrop()
                        .into(binding.toolbar) { view, drawable ->
                            view.navigationIcon = drawable
                        }
                }
            }
            binding.toolbar.setNavigationOnClickListener {
                if (model.avatar.value ?: 0 == 0) return@setNavigationOnClickListener
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), it, "shared_element_container")
                startActivity(Intent(requireContext(), PreviewActivity::class.java).putExtra("query", Q().id(model.avatar.value!!)), options.toBundle())
            }
            model.avatar.observe(viewLifecycleOwner, Observer {
                if (model.background.value != null) return@Observer
                lifecycleScope.launchWhenCreated { background(it) }
            })
            lifecycleScope.launchWhenCreated {
                model.background.asFlow().mapNotNull { it }.collectLatest { url ->
                    GlideApp.with(binding.image)
                        .load(url)
                        .transform(AlphaBlackBitmapTransformation())
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(binding.image)
                }
            }
            (binding.recycler.layoutManager as? FlexboxLayoutManager)?.apply {
                flexWrap = FlexWrap.WRAP
                flexDirection = FlexDirection.ROW
                alignItems = AlignItems.FLEX_START
                justifyContent = JustifyContent.FLEX_START
            }
            adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            binding.recycler.adapter = adapter
            lifecycleScope.launchWhenCreated {
                val flowUser = model.user.asFlow().mapNotNull { it?.takeIf { it != 0 } }
                val flowName = OAuth.name.asFlow().mapNotNull { it.takeIf { it.isNotEmpty() } }
                flowOf(flowUser, flowName).flattenMerge(2).collectLatest {
                    if (model.data.value?.isEmpty() != false) query()
                }
            }
            model.data.observe(viewLifecycleOwner, Observer {
                adapter.submitList(it.toList())
            })
            binding.swipe.setOnRefreshListener {
                lifecycleScope.launchWhenCreated { query() }
            }
            model.busy.observe(viewLifecycleOwner, Observer { binding.swipe.isRefreshing = it })
        }.root

    private suspend fun background(id: Int) {
        val list = withContext(Dispatchers.IO) {
            runCatching { Service.instance.post(1, Q().id(id), 1) }.getOrNull()
        }
        list?.firstOrNull()?.sample_url?.let { model.background.postValue(it) }
    }

    private suspend fun query() {
        if (!OAuth.available) return
        val name = model.name.value ?: return
        val user = model.user.value ?: return
        model.busy.postValue(true)
        val tags = listOf("vote:3:$name order:vote" to listOf("Favorite Artists", "Favorite Copyrights", "Favorite Characters", "Favorite Styles", "Favorite Circles"), "user:$name" to listOf("Uploaded Tags", "Uploaded Artists", "Uploaded Copyrights", "Uploaded Characters", "Uploaded Styles", "Uploaded Circles"))
        val images = listOf("Favorites" to "vote:3:$name order:vote", "Uploads" to "user:$name")
        val data = (listOf("Common" to null) + tags.flatMap { it.second }.map { it to null } + images).map { it.first to (mutableListOf<Parcelable>() to it.second) }.toMap()
        coroutineScope {
            launch {
                val jsoup = withContext(Dispatchers.Default) {
                    val url = "$moeUrl/user/show/$user"
                    val html = okHttp.newCall(Request.Builder().url(url).build()).await { _, response -> response.body?.string() }
                    Jsoup.parse(html, url)
                }
                launch {
                    val id = jsoup.select("img.avatar").parents().firstOrNull { it.tagName() == "a" }?.attr("href")?.let { Regex("\\d+").find(it) }?.value?.toInt() ?: 0
                    model.avatar.postValue(id)
                }
                launch {
                    val posts = jsoup.select("td:contains(Posts)").next().firstOrNull()?.text()?.toIntOrNull() ?: 0
                    if (posts > 0) {
                        data["Common"]?.first?.add(Tag("Posts: ${posts}P", "user:$name"))
                    }
                    val votes = jsoup.select("th:contains(Votes)").next().select(".stars a").map { it.text().trim('★', ' ').toIntOrNull() ?: 0 }.zip(1..3).filter { it.first > 0 }
                    for (v in votes) {
                        data["Common"]?.first?.add(Tag("Vote ${v.second}: ${v.first}P", "vote:${v.second}:$name order:vote"))
                    }
                    if (votes.size > 1) data["Common"]?.first?.add(Tag("Vote all: ${votes.sumBy { it.first }}P", "vote:1..3:$name order:vote"))
                    for (i in tags) {
                        for (j in i.second) {
                            val m = jsoup.select("th:contains($j)").next().select("a").map { it.text() }
                            data[j]?.first?.addAll(m.map { Tag(it, i.first + " ${it.replace(' ', '_')}") })
                        }
                    }
                    val submit = data.filter { it.value.first.any() }.flatMap { listOf(Title(it.key, it.value.second)) + it.value.first }
                    model.data.postValue(submit.toTypedArray())
                }
            }
            images.map { image ->
                launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { Service.instance.post(1, Q(image.second)) }.getOrElse { emptyList() }
                    }
                    data[image.first]?.first?.addAll(result)
                    val submit = data.filter { it.value.first.any() }.flatMap { listOf(Title(it.key, it.value.second)) + it.value.first }
                    model.data.postValue(submit.toTypedArray())
                }
            }
        }
        model.busy.postValue(false)
    }

    @Parcelize
    data class Title(val name: String, val query: String? = null) : Parcelable
    class TitleHolder(private val binding: UserTitleItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.button1.setOnClickListener {
                val options = ActivityOptions.makeSceneTransitionAnimation(binding.root.context as Activity, binding.root, "shared_element_container")
                binding.root.context.startActivity(Intent(binding.root.context, ListActivity::class.java).putExtra("query", Q(tag?.query)), options.toBundle())
            }
        }

        var tag: Title? = null
        fun bind(tag: Title) {
            this.tag = tag
            binding.text1.text = tag.name
            binding.button1.isVisible = tag.query != null
        }
    }

    @Parcelize
    data class Tag(val name: String, val query: String) : Parcelable
    inner class TagHolder(private val binding: UserTagItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setCardBackgroundColor(randomColor())
            binding.root.setOnClickListener {
                val options = ActivityOptions.makeSceneTransitionAnimation(requireActivity(), binding.root, "shared_element_container")
                startActivity(Intent(binding.root.context, ListActivity::class.java).putExtra("query", Q(tag?.query)), options.toBundle())
            }
        }

        var tag: Tag? = null
        fun bind(tag: Tag) {
            this.tag = tag
            binding.text1.text = tag.name
        }
    }

    class ImageHolder(private val binding: UserImageItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            (binding.root.layoutParams as? FlexboxLayoutManager.LayoutParams)?.flexGrow = 1.0f
        }

        fun bind(item: JImageItem) {
            binding.root.minimumWidth = binding.root.resources.getDimensionPixelSize(R.dimen.user_image_height) * item.preview_width / item.preview_height
            bindImageFromUrl(binding.image1, item.preview_url, binding.progress, R.mipmap.ic_launcher_foreground)
        }
    }

    inner class ImageAdapter : ListAdapter<Parcelable, RecyclerView.ViewHolder>(diffCallback { old, new -> old == new }) {
        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is Title -> 0
            is Tag -> 1
            is JImageItem -> 2
            else -> throw IllegalArgumentException()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> TitleHolder(UserTitleItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            1 -> TagHolder(UserTagItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            2 -> ImageHolder(UserImageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> throw IllegalArgumentException()
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int): Unit = when (holder) {
            is TitleHolder -> holder.bind(getItem(position) as Title)
            is TagHolder -> holder.bind(getItem(position) as Tag)
            is ImageHolder -> holder.bind(getItem(position) as JImageItem)
            else -> throw IllegalArgumentException()
        }
    }
}