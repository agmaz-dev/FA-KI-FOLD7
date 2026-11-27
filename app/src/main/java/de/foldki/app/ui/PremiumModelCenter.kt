package de.foldki.app.ui

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foldki.app.ChatViewModel
import de.foldki.app.UiState
import de.foldki.app.ai.HfModels
import de.foldki.app.ai.LocalEngine
import de.foldki.app.ai.LocalModelManager
import de.foldki.app.ai.LocalModelManager.State
import de.foldki.app.data.ModelParams
import de.foldki.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class MP(val bg: Color, val card: Color, val text: Color, val muted: Color) {
    val blue = Color(0xFF8060F5)
    val green = Color(0xFF32BF8A)
    val line = muted.copy(alpha = .22f)
}
private val DarkMP = MP(Color(0xFF0A0A0C), Color(0xFF131315), Color(0xFFF4F3F6), Color(0xFF95939E))
private val LightMP = MP(Color(0xFFF6F5F7), Color.White, Color(0xFF222126), Color(0xFF77747F))
private val Palette = staticCompositionLocalOf { DarkMP }

@Composable private fun MText(s: String, size: Int = 14, muted: Boolean = false, bold: Boolean = false, modifier: Modifier = Modifier, color: Color? = null) {
    val p = Palette.current
    Text(s, modifier, color = color ?: if (muted) p.muted else p.text, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
}
@Composable private fun MCard(modifier: Modifier = Modifier, active: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = Palette.current; val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(p.card).then(if (active) Modifier.border(1.dp, p.blue.copy(alpha=.65f), shape) else Modifier).padding(8.dp), verticalArrangement=Arrangement.spacedBy(5.dp), content=content)
}
@Composable private fun MButton(label: String, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true, action: () -> Unit) {
    val p=Palette.current
    if(filled) Button(action, modifier, enabled=enabled, shape=RoundedCornerShape(12.dp), colors=ButtonDefaults.buttonColors(containerColor=p.blue,contentColor=Color.White)) { Text(label, fontSize=13.sp) }
    else OutlinedButton(action, modifier, enabled=enabled, shape=RoundedCornerShape(12.dp), border=androidx.compose.foundation.BorderStroke(1.dp,p.line), colors=ButtonDefaults.outlinedButtonColors(contentColor=p.blue)) { Text(label,fontSize=13.sp) }
}
@Composable private fun MIcon(icon: ImageVector, label: String, action: () -> Unit) {
    val p=Palette.current
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(p.card).clickable(onClick=action),contentAlignment=Alignment.Center) { Icon(icon,label,tint=p.blue,modifier=Modifier.size(20.dp)) }
}
@Composable private fun MLogo(vendor: String, size: Int=28) {
    val c=when(vendor.lowercase()) { "google" -> Color(0xFF4285F4); "alibaba" -> Color(0xFF9471EC); "openai" -> Color(0xFF2CAE91); else -> Palette.current.blue }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha=.15f)),contentAlignment=Alignment.Center) { Text(vendor.take(1).uppercase(),color=c,fontWeight=FontWeight.Bold,fontSize=(size*.46).sp) }
}
@Composable private fun MTags(tags: List<String>) {
    val p=Palette.current
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        tags.distinct().forEach { Text(it,color=p.muted,fontSize=11.sp,modifier=Modifier.clip(RoundedCornerShape(6.dp)).background(p.muted.copy(alpha=.12f)).padding(horizontal=7.dp,vertical=4.dp)) }
    }
}
@Composable private fun MSearch(value:String,change:(String)->Unit,hint:String) {
    val p=Palette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(Icons.Default.Search,null,tint=p.muted,modifier=Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { if(value.isEmpty()) MText(hint,muted=true); BasicTextField(value,change,singleLine=true,textStyle=TextStyle(color=p.text,fontFamily=Inter,fontSize=14.sp),modifier=Modifier.fillMaxWidth()) }
        if(value.isNotEmpty()) Icon(Icons.Default.Close,"Leeren",tint=p.muted,modifier=Modifier.size(20.dp).clickable{change("")})
    }
}
@Composable private fun MTitle(s:String) { MText(s,18,bold=true) }
@Composable private fun MProgress(f:Float) { val p=Palette.current; LinearProgressIndicator(progress={f.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(8.dp)),color=p.blue,trackColor=p.line) }
private fun modelVendor(s:String)=if(s.contains("deepseek",true)) "DeepSeek" else if(s.contains("gpt-oss",true)) "OpenAI" else HfModels.vendorOf(s)
private fun modelFormat(file:String)=if(HfModels.isGguf(file)) "GGUF · llama.cpp" else "LiteRT-LM"
private fun modelTitle(m:LocalModelManager.LocalModel)=HfModels.prettyName(m.name)

@Composable
fun PremiumModelCenter(vm:ChatViewModel,ui:UiState,initialDiscover:Boolean,onBack:()->Unit,onNano:()->Unit,onServer:()->Unit,initialDownloads:Boolean=false,workspace:Boolean=false) {
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("model_design",Context.MODE_PRIVATE) }
    var dark by rememberSaveable { mutableStateOf(prefs.getBoolean("dark",true)) }
    var page by rememberSaveable(initialDiscover,initialDownloads) { mutableStateOf(if(initialDownloads) "Downloads" else if(initialDiscover) "Entdecken" else "Meine Modelle") }
    var repo by rememberSaveable { mutableStateOf("") }
    var installed by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf<LocalModelManager.LocalModel?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var linkError by rememberSaveable { mutableStateOf("") }
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if(it!=null) { vm.importModel(it); page="Downloads" } }
    val busy=ui.modelOp is State.Downloading || ui.modelOp is State.Verifying || ui.modelOp is State.Importing
    fun openDetails(m:LocalModelManager.LocalModel) { installed=m.file; repo=""; page="Modelldetails" }
    fun openConfig(m:LocalModelManager.LocalModel) { installed=m.file; page="Konfiguration" }
    fun back() { when(page) { "Modelldetails" -> page=if(repo.isNotEmpty()) "Entdecken" else "Meine Modelle"; "Konfiguration","Importieren","Downloads","Entdecken" -> page="Meine Modelle"; "Hugging Face" -> page="Entdecken"; else -> onBack() } }
    BackHandler { back() }
    CompositionLocalProvider(Palette provides if(dark) DarkMP else LightMP) {
        val p=Palette.current
        MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=p.blue,background=p.bg,surface=p.card,onSurface=p.text) else lightColorScheme(primary=p.blue,background=p.bg,surface=p.card,onSurface=p.text)) {
            Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(!workspace || page in listOf("Modelldetails","Konfiguration","Hugging Face")) MIcon(Icons.AutoMirrored.Filled.ArrowBack,"Zurück"){back()}
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        val tabs = if(initialDownloads) listOf("Downloads") else if(workspace) listOf("Meine Modelle","Entdecken","Importieren") else listOf("Meine Modelle","Entdecken","Downloads","Importieren")
                        tabs.forEach { t ->
                            Text(if(t=="Meine Modelle") "Installiert" else t, color=if(page==t)p.blue else p.muted, fontSize=13.sp, modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(if(page==t)p.blue.copy(alpha=.13f) else Color.Transparent).clickable{page=t}.padding(horizontal=10.dp,vertical=12.dp))
                        }
                    }
                    MIcon(if(dark) Icons.Default.LightMode else Icons.Default.DarkMode,"Darstellung"){dark=!dark;prefs.edit().putBoolean("dark",dark).apply()}
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when(page) {
                        "Meine Modelle" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            MTitle("Meine Modelle")
                            val used=ui.localModels.sumOf{it.size}; val free=remember(ui.localModels,ui.modelOp){vm.models.freeBytes()}
                            MCard {
                                Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.Storage,null,tint=p.blue,modifier=Modifier.size(30.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)){MText("Modellspeicher",12,true);MText(LocalModelManager.gb(used),22,bold=true)}; MText(ui.localModels.size.toString()+" Modelle",12,true) }
                                if(used+free>0) MProgress(used.toFloat()/(used+free))
                                MText("Frei: "+LocalModelManager.gb(free),12,true)
                            }
                            if(busy && !workspace) MButton("Download / Import anzeigen",Modifier.fillMaxWidth(),false){page="Downloads"}
                            ui.activeLocal?.let{m-> MText("Aktives Modell",12,true); InstalledCard(vm,ui,m,true,{openDetails(m)},{openConfig(m)},{delete=m})}
                            if(ui.localModels.any{it.file!=ui.activeModel}) MText("Installierte Modelle",12,true)
                            ui.localModels.filter{it.file!=ui.activeModel}.forEach{m->InstalledCard(vm,ui,m,false,{openDetails(m)},{openConfig(m)},{delete=m})}
                            MCard(active=ui.usesNano) { Row(verticalAlignment=Alignment.CenterVertically){MLogo("Google");Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){MText("Gemini Nano",16,bold=true);MText(if(ui.nanoReady) "Android AICore · bereit" else ui.nanoStatus,12,true)};MButton(if(ui.usesNano)"Details" else "Aktivieren"){vm.selectModel(Settings.NANO);onNano()}} }

                        }
                        "Entdecken" -> DiscoverPage(ui,{repo=it;installed="";page="Modelldetails"},{page="Hugging Face"})
                        "Hugging Face" -> WebMode(vm,{repo=it;installed="";page="Modelldetails"},{page="Entdecken"})
                        "Modelldetails" -> if(repo.isNotBlank()) RemoteDetails(vm,ui,repo){page="Downloads"} else {
                            val m=ui.localModels.firstOrNull{it.file==installed}
                            if(m==null) Column { MTitle("Modelldetails");MText("Wähle zuerst ein Modell.",14,true);MButton("Meine Modelle"){page="Meine Modelle"} }
                            else Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                MTitle("Modelldetails"); MCard { Row(verticalAlignment=Alignment.CenterVertically){MLogo(modelVendor(m.file),64);Spacer(Modifier.width(12.dp));Column{MText(modelTitle(m),20,bold=true);MText(modelFormat(m.file),12,true)}};MText(m.file,12,true);MText(LocalModelManager.gb(m.size),20,bold=true);MText("Quelle: "+m.source,13,true);MText("RAM-Einschätzung: "+HfModels.fitText(vm.models.totalRam(),m.size).first,12,true) }
                                MButton("Modell konfigurieren",Modifier.fillMaxWidth()){openConfig(m)}
                                MButton("Aktivieren",Modifier.fillMaxWidth(),false){vm.selectModel(m.file);page="Meine Modelle"}
                                MButton("Entfernen",Modifier.fillMaxWidth(),false){delete=m}
                            }
                        }
                        "Downloads" -> DownloadPage(vm,ui){page="Entdecken"}
                        "Konfiguration" -> { val m=ui.localModels.firstOrNull{it.file==installed};if(m==null) Column{MTitle("Konfiguration");MText("Wähle ein installiertes Modell.",14,true);MButton("Meine Modelle"){page="Meine Modelle"}} else ConfigPage(vm,m){page="Meine Modelle"} }
                        "Importieren" -> Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            MTitle("Modell hinzufügen");MText("Wähle die Quelle deines KI-Modells.",14,true)
                            ImportCard(Icons.Default.FolderOpen,"Lokale Datei","GGUF oder LiteRT-LM vom Gerätespeicher"){if(!busy)pick.launch(arrayOf("*/*")) else vm.notify("Ein Vorgang läuft bereits.")}

                            MCard { MText("Download-Link",16,bold=true); MSearch(url,{url=it;linkError=""},"Hugging-Face-URL");if(linkError.isNotBlank())MText(linkError,12,true)
                                MButton("Link öffnen",Modifier.fillMaxWidth()){val id=HfModels.repoFromUrl(url.trim());if(id==null)linkError="Bitte einen gültigen Hugging-Face-Modell-Link eingeben." else{repo=id;page="Modelldetails"}} }
                            if(!workspace) ImportCard(Icons.Default.Dns,"PC-Server","Verbindung zu einer externen KI-Engine"){onServer()}
                        }
                    }
                }

            }
            delete?.let{m->AlertDialog(onDismissRequest={delete=null},title={Text("Modell löschen?")},text={Text(m.name+" wird vom Gerät entfernt.")},confirmButton={TextButton({vm.deleteModel(m.file);delete=null;page="Meine Modelle"}){Text("Löschen")}},dismissButton={TextButton({delete=null}){Text("Abbrechen")}})}
        }
    }
}

@Composable private fun InstalledCard(vm:ChatViewModel,ui:UiState,m:LocalModelManager.LocalModel,active:Boolean,details:()->Unit,config:()->Unit,remove:()->Unit) {
    val p=Palette.current;val gguf=HfModels.isGguf(m.file);val params=vm.settings.modelParams(m.file)
    MCard(active=active) {
        Row(Modifier.clickable(onClick=details),verticalAlignment=Alignment.CenterVertically){MLogo(modelVendor(m.file));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){MText(modelTitle(m),16,bold=true);MText(modelVendor(m.file)+" · "+modelFormat(m.file)+" · "+LocalModelManager.gb(m.size),12,true)};Icon(if(active)Icons.Default.CheckCircle else Icons.Default.ChevronRight,null,tint=if(active)p.green else p.muted)}
        MTags(HfModels.featuresOf(m.file,emptyList(),"")+listOf(modelFormat(m.file))+listOfNotNull(HfModels.bitsOf(m.file).takeIf{it.isNotBlank()}))
        if(active){HorizontalDivider(color=p.line);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Column(Modifier.weight(1f)){MText("Hardware",11,true);MText(ui.backend.ifBlank{vm.settings.gemmaBackend},13,bold=true)};Column(Modifier.weight(1f)){MText("Kontext",11,true);MText((if(gguf)de.foldki.app.ai.GgufEngine.N_CTX else params.context.takeIf{it>0}?:LocalEngine.contextFor(m.file)).toString()+" Tokens",13,bold=true)};Column(Modifier.weight(1f)){MText("Engine",11,true);MText(if(gguf)"llama.cpp" else "0.20.0",13,bold=true)}}}
        MButton("Modell konfigurieren",Modifier.fillMaxWidth(),false,action=config)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){if(!active)MButton("Aktivieren",Modifier.weight(1f)){vm.selectModel(m.file)};MButton("Details",Modifier.weight(1f),false,action=details);MButton("Entfernen",Modifier.weight(1f),false,action=remove)}
    }
}
@Composable private fun ImportCard(icon:ImageVector,title:String,sub:String,action:()->Unit){val p=Palette.current;MCard(Modifier.clickable(onClick=action)){Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(p.blue.copy(alpha=.13f)),contentAlignment=Alignment.Center){Icon(icon,null,tint=p.blue)};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){MText(title,16,bold=true);MText(sub,12,true)};Icon(Icons.Default.ChevronRight,null,tint=p.muted)}}}

@Composable private fun DiscoverPage(ui:UiState,details:(String)->Unit,browser:()->Unit){
    val p=Palette.current
    var query by rememberSaveable{mutableStateOf("")};var filter by rememberSaveable{mutableStateOf("Alle")};var format by rememberSaveable{mutableStateOf("LiteRT-LM")}
    var repos by remember{mutableStateOf<List<HfModels.Repo>?>(null)};var error by remember{mutableStateOf("")};var refresh by remember{mutableIntStateOf(0)}
    LaunchedEffect(format,query,refresh){if(format=="GGUF")kotlinx.coroutines.delay(350);repos=null;error="";try{repos=withContext(Dispatchers.IO){if(format=="GGUF")HfModels.fetchGguf("downloads",query)else HfModels.fetchOfficial()}}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=e.message.orEmpty()}}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(5.dp)){
        MSearch(query,{query=it},"Modell oder Hersteller suchen …")
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("LiteRT-LM","GGUF").forEach{t-> Text(t,color=if(format==t)p.blue else p.muted,fontSize=13.sp,modifier=Modifier.clickable{format=t}.padding(8.dp))}}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("Alle","Bilder","Denken","Werkzeuge","Klein").forEach{t->Text(t,color=if(filter==t)p.blue else p.muted,fontSize=12.sp,modifier=Modifier.clickable{filter=t}.padding(8.dp))}}
        Row(verticalAlignment=Alignment.CenterVertically){MText("Hugging Face · Modellkatalog",12,true,modifier=Modifier.weight(1f));MButton("Website",filled=false,action=browser)}
        val shown=repos.orEmpty().filter{r-> !Regex("embed|parakeet|whisper|speech|rerank|wav2vec|moonshine|kokoro|piper",RegexOption.IGNORE_CASE).containsMatchIn(r.id) && (query.isBlank()||format=="GGUF"||(r.name+" "+modelVendor(r.id)).contains(query,true))&&(filter=="Alle"||filter in r.features||(filter=="Klein"&&(r.parameter.endsWith("M")||r.parameter.dropLast(1).toDoubleOrNull()?.let{it<=2}==true)))}
        when{error.isNotBlank()->MCard{MText(error,14,true);MButton("Erneut versuchen"){refresh++}};repos==null->Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(color=p.blue)};shown.isEmpty()->MText("Keine passenden Modelle.",14,true);else->LazyColumn(verticalArrangement=Arrangement.spacedBy(5.dp)){items(shown,key={it.id}){r->MCard(Modifier.clickable{details(r.id)}){Row(verticalAlignment=Alignment.CenterVertically){MLogo(modelVendor(r.id));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){MText(HfModels.prettyName(r.name),16,bold=true);MText(modelVendor(r.id)+" · "+format,12,true);MText(r.downloads.toString()+" Downloads · Dateigröße in Details",11,true)};Icon(Icons.Default.ChevronRight,null,tint=p.muted)};MTags(r.features+listOfNotNull(r.parameter.takeIf{it.isNotBlank()})+if(r.usableFiles.any{f->ui.localModels.any{it.file==f.substringAfterLast('/')}})listOf("Installiert")else emptyList())}}}}
    }
}

@Composable private fun RemoteDetails(vm:ChatViewModel,ui:UiState,id:String,downloads:()->Unit){
    val p=Palette.current;var r by remember(id){mutableStateOf<HfModels.Repo?>(null)};var files by remember(id){mutableStateOf<List<HfModels.LmFile>>(emptyList())};var error by remember(id){mutableStateOf("")};var selected by rememberSaveable(id){mutableStateOf("")};var warn by remember{mutableStateOf<HfModels.LmFile?>(null)};var description by remember(id){mutableStateOf("")}
    LaunchedEffect(id){try{val x=withContext(Dispatchers.IO){HfModels.fetchRepo(id) to HfModels.fetchFiles(id)};r=x.first;files=x.second.filter{it.usable};if(selected.isBlank())selected=HfModels.recommend(files)?.path.orEmpty();description=withContext(Dispatchers.IO){HfModels.fetchReadme(id)}}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=e.message.orEmpty()}}
    val f=files.firstOrNull{it.path==selected};val ram=remember{vm.models.totalRam()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
        MTitle("Modelldetails")
        MCard{Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){MLogo(modelVendor(id),64);Spacer(Modifier.height(8.dp));MText(HfModels.prettyName(id.substringAfter('/')),22,bold=true);MText(modelVendor(id)+" · Lokales Sprachmodell",13,true)};MTags(r?.features.orEmpty());if(f!=null)MText(HfModels.fitText(ram,f.size).first.ifBlank{"Kompatibilität unbekannt"},13,color=p.muted)}
        if(error.isNotBlank())MText(error,14,true) else if(r==null)CircularProgressIndicator(color=p.blue)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){MCard(Modifier.weight(1f)){MText("Format",12,true);MText(f?.let{modelFormat(it.fileName)}?:"–",14,bold=true)};MCard(Modifier.weight(1f)){MText("Dateigröße",12,true);MText(f?.size?.takeIf{it>0}?.let{LocalModelManager.gb(it)}?:"–",14,bold=true)}}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){MCard(Modifier.weight(1f)){MText("Ausführung",12,true);MText("Lokal",14,bold=true)};MCard(Modifier.weight(1f)){MText("Internet",12,true);MText("Für Download",14,bold=true)}}
        MText("Modelldateien",16,bold=true)
        files.forEach{file->MCard(Modifier.clickable{selected=file.path},active=selected==file.path){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){MText(file.fileName,14,bold=true);MText(LocalModelManager.gb(file.size)+" · "+file.bits+" · "+HfModels.fitText(ram,file.size).first,12,true);if(file==HfModels.recommend(files))MText("Empfohlen",12,color=p.green)};RadioButton(selected==file.path,{selected=file.path})}}}
        if(r!=null&&files.isEmpty())MText("Keine unterstützte Modelldatei gefunden.",14,true)
        val have=f!=null&&ui.localModels.any{it.file==f.fileName}
        MButton(if(have)"Bereits installiert" else "Modell herunterladen",Modifier.fillMaxWidth(),enabled=f!=null&&!have&&!vm.models.busy){f?.let{if(HfModels.tooBigFor(ram,it.size))warn=it else{vm.downloadModel(it);downloads()}}}
        if(description.isNotBlank())MCard{MText("Über das Modell",16,bold=true);MText(description.take(1200),13,true)}
    }
    warn?.let{file->BigModelWarning(file,ram,{warn=null},{warn=null;vm.downloadModel(file);downloads()})}
}

@Composable private fun DownloadPage(vm:ChatViewModel,ui:UiState,discover:()->Unit){
    val p=Palette.current; val st=ui.modelOp
    var lastBytes by remember{mutableLongStateOf(0L)};var lastTime by remember{mutableLongStateOf(0L)};var speed by remember{mutableDoubleStateOf(0.0)}
    val d=st as? State.Downloading
    LaunchedEffect(d?.name){lastBytes=0;lastTime=0;speed=0.0}
    LaunchedEffect(d?.bytes){if(d!=null){val now=android.os.SystemClock.elapsedRealtime();if(lastTime>0&&d.bytes>=lastBytes&&now>lastTime)speed=(d.bytes-lastBytes)*1000.0/(now-lastTime);lastBytes=d.bytes;lastTime=now}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
        MTitle("Downloads")
        when(st){
            is State.Downloading->MCard{Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Download,null,tint=p.blue,modifier=Modifier.size(30.dp));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){MText(HfModels.displayName(st.name),16,bold=true);MText("Download läuft",12,true)};MText(st.pct.toString()+" %",24,bold=true,color=p.blue)};MProgress(st.pct/100f);MText(LocalModelManager.gb(st.bytes)+" von "+(if(st.total>0)LocalModelManager.gb(st.total)else "unbekannt"),12,true);if(speed>0){MText("%.1f MB/s".format(java.util.Locale.GERMANY,speed/1e6),12,true);if(st.total>st.bytes)MText("Restzeit ca. "+((st.total-st.bytes)/speed/60).toInt().coerceAtLeast(1)+" Min.",12,true)};MButton("Abbrechen",Modifier.fillMaxWidth(),false){vm.cancelModelOp()}}
            is State.Verifying->MCard{MText("Datei wird geprüft",16,bold=true);MText(st.name,12,true);MProgress(st.pct/100f);MText("Größe und SHA-256 · "+st.pct+" %",12,true)}
            is State.Importing->MCard{MText("Importiere "+st.name,16,bold=true);MProgress(st.pct/100f);MButton("Abbrechen",Modifier.fillMaxWidth(),false){vm.cancelModelOp()}}
            is State.Failed->MCard{MText(st.message,14);MButton("Schließen"){vm.clearModelError()}}
            State.Idle->MCard{Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.Download,null,tint=p.muted,modifier=Modifier.size(40.dp));Spacer(Modifier.height(10.dp));MText("Keine aktiven Downloads");Spacer(Modifier.height(10.dp));MButton("Modelle entdecken",filled=false,action=discover)}}
        }

    }
}

@Composable private fun ConfigPage(vm:ChatViewModel,m:LocalModelManager.LocalModel,done:()->Unit){
    val p=Palette.current;val start=remember(m.file){vm.settings.modelParams(m.file)};val gguf=HfModels.isGguf(m.file);val context=LocalContext.current
    val npuBuilt=remember{de.foldki.app.ai.GgufEngine.npuBuilt(context)}
    var backend by remember(m.file){mutableStateOf(if(gguf&&vm.settings.npu&&npuBuilt&&vm.settings.gemmaBackend!="CPU")"NPU" else vm.settings.gemmaBackend)}
    var ctx by remember(m.file){mutableIntStateOf(start.context)};var temp by remember(m.file){mutableFloatStateOf(start.temperature)};var max by remember(m.file){mutableFloatStateOf(start.maxTokens.toFloat())};var topK by remember(m.file){mutableFloatStateOf(start.topK.toFloat())};var topP by remember(m.file){mutableFloatStateOf(start.topP)};var think by remember(m.file){mutableStateOf(start.think)};var gpu by remember(m.file){mutableFloatStateOf(vm.settings.gpuPercent.toFloat())};var power by remember{mutableStateOf(vm.settings.npuPower)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
        MTitle("Konfiguration");MText(modelTitle(m),14,true)
        MCard{MText("Hardware",16,bold=true);listOf("GPU","CPU","NPU").forEach{b->val enabled=b!="NPU"||gguf&&npuBuilt;Row(Modifier.fillMaxWidth().clickable(enabled=enabled){backend=b},verticalAlignment=Alignment.CenterVertically){RadioButton(backend==b,{backend=b},enabled=enabled);MText(b+when(b){"GPU"->" – Grafikprozessor";"CPU"->" – Hauptprozessor";else->if(enabled)" – KI-Prozessor" else " – nicht verfügbar"},14,muted=!enabled)}}
            if(!gguf){HorizontalDivider(color=p.line);MText("Kontextfenster",16,bold=true);Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf(0,2048,4096,8192).forEach{n->MButton(if(n==0)"Datei" else (n/1024).toString()+"K",Modifier.weight(1f),ctx==n){ctx=n}}};MText("Höherer Kontext benötigt mehr Arbeitsspeicher.",12,true)}
            else{MText("GPU-Anteil: "+gpu.toInt()+" %");Slider(gpu,{gpu=it},valueRange=0f..100f);if(npuBuilt){MText("NPU-Leistung");Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("power_saver" to "Sparsam","balanced" to "Normal","burst" to "Maximal").forEach{(v,l)->MButton(l,Modifier.weight(1f),power==v){power=v}}}}}
        }
        MCard{MText("Temperatur · "+if(temp<0)"Standard" else "%.1f".format(java.util.Locale.GERMANY,temp),16,bold=true);Slider(temp.coerceIn(-.1f,1.5f),{temp=if(it<0)-1f else it},valueRange=-.1f..1.5f);Row{MText("Präzise",12,true);Spacer(Modifier.weight(1f));MText("Kreativ",12,true)};HorizontalDivider(color=p.line);Row{MText("Engine-Version");Spacer(Modifier.weight(1f));MText(if(gguf)"llama.cpp" else "0.20.0",13,true)}}
        MCard{MText("Erweiterte Parameter",16,bold=true);MText("Höchstens Tokens: "+if(max<1)"Ohne Grenze" else max.toInt().toString());Slider(max.coerceIn(0f,4000f),{max=it},valueRange=0f..4000f,steps=15);MText("Top-K: "+topK.toInt());Slider(topK.coerceIn(0f,100f),{topK=it},valueRange=0f..100f,steps=19);MText("Top-P: "+"%.2f".format(topP));Slider(topP.coerceIn(0f,1f),{topP=it});MText("Denken");Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("" to "Standard","off" to "Aus","on" to "An").forEach{(v,l)->MButton(l,Modifier.weight(1f),think==v){think=v}}}}
        MButton("Einstellungen übernehmen",Modifier.fillMaxWidth()){vm.settings.npu=backend=="NPU";vm.setCpu(backend=="CPU");if(gguf){vm.setGpuPercent(gpu.toInt());if(npuBuilt&&power!=vm.settings.npuPower)vm.setNpuPower(power)};vm.saveModelParams(m.file,ModelParams(max.toInt(),topK.toInt(),topP,temp,think,ctx));done()}
        MButton("Empfohlene Modellparameter",Modifier.fillMaxWidth(),false){vm.saveModelParams(m.file,null);done()}
    }
}
