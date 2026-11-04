// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Short-lived LAN file access to the Android shared public-storage directory. */
class LibraryTransferServer(
    address: String,
    directory: java.io.File,
    private val onExpired: () -> Unit = {},
    ttlMillis: Long = 300_000,
    port: Int = DEFAULT_PORT
) : Closeable {
    private val stopped=AtomicBoolean(false)
    private val expiry=Timer("hongguotv-file-transfer-expiry",true)
    private val root=directory.canonicalFile
    private val local=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener=ServerSocket(port,4,local).apply { reuseAddress=false; soTimeout=250 }
    private val activeSockets=ConcurrentHashMap.newKeySet<Socket>()
    private val lifetime=ttlMillis.coerceIn(1,300_000)
    private val deadline=System.nanoTime()+lifetime*1_000_000
    private val host="${local.hostAddress}:${listener.localPort}"
    val url="http://$host/"

    init {
        if(!root.exists() && !root.mkdirs()) error("无法创建公共目录")
        require(root.isDirectory && root.canRead() && root.canWrite()) { "公共目录不可读写" }
        expiry.schedule(object:TimerTask(){override fun run()=expire()},lifetime)
        Thread({serve()},"hongguotv-file-transfer").apply { isDaemon=true; start() }
    }
    private fun expire(){if(stopped.compareAndSet(false,true)){close();onExpired()}}
    override fun close(){stopped.set(true);expiry.cancel();runCatching{listener.close()};activeSockets.toList().forEach{runCatching{it.close()}}}
    private fun serve(){
        try{
            while(!stopped.get() && System.nanoTime()<deadline){
                val socket=try{listener.accept()}catch(_:SocketTimeoutException){continue}
                Thread({
                    activeSockets.add(socket)
                    try{socket.use{it.soTimeout=30_000;if(!stopped.get())handle(it)}}
                    catch(_:Exception){}
                    finally{activeSockets.remove(socket)}
                },"hongguotv-file-transfer-conn").apply{isDaemon=true;start()}
            }
        }catch(_:Exception){}
        finally{if(!stopped.get())expire() else close()}
    }
    private fun line(input:InputStream,max:Int):String{
        val out=ByteArrayOutputStream()
        while(out.size()<max){val b=input.read();if(b<0)throw java.io.EOFException();if(b==10)return out.toString("US-ASCII").trimEnd('\r');out.write(b)}
        throw java.io.IOException("请求头过长")
    }
    private fun query(target:String):Pair<String,Map<String,String>>{
        val at=target.indexOf('?')
        if(at<0)return target to emptyMap()
        val values=target.substring(at+1).split('&').mapNotNull{part->
            val p=part.indexOf('=')
            if(p<0)null else runCatching{URLDecoder.decode(part.substring(0,p),"UTF-8") to URLDecoder.decode(part.substring(p+1),"UTF-8")}.getOrNull()
        }.toMap()
        return target.substring(0,at) to values
    }
    private fun resolve(relative:String):java.io.File{
        val target=java.io.File(root,relative.trimStart('/')).canonicalFile
        require(target.path==root.path || target.path.startsWith(root.path+java.io.File.separator)){"路径无效"}
        return target
    }
    private fun enc(value:String)=URLEncoder.encode(value,"UTF-8")
    private fun html(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
    private fun handle(socket:Socket){
        if(!socket.inetAddress.let{it.isLoopbackAddress||it.isSiteLocalAddress})return
        val input=socket.getInputStream().buffered()
        val request=line(input,2048).split(' ')
        if(request.size!=3)return
        val headers=mutableMapOf<String,String>();var total=0
        while(true){
            val h=line(input,4096);total+=h.length+2
            if(total>8192)return respond(socket,431,"请求头过大")
            if(h.isEmpty())break
            val at=h.indexOf(':');if(at<=0)return respond(socket,400,"请求格式错误")
            if(headers.put(h.substring(0,at).lowercase(Locale.ROOT),h.substring(at+1).trim())!=null)return respond(socket,400,"重复请求头")
        }
        if(stopped.get()||System.nanoTime()>=deadline)return respond(socket,410,"文件管理会话已过期")
        val (route,params)=query(request[1])
        if(headers["host"]!=host||route !in listOf("/","/download","/upload"))return respond(socket,404,"页面不存在")
        val origin=headers["origin"]
        if(origin!=null){
            val parsed=runCatching{java.net.URI(origin)}.getOrNull()
            if(parsed==null||parsed.scheme!="http"||parsed.host!=local.hostAddress||parsed.port!=listener.localPort)return respond(socket,403,"来源不匹配")
        }
        if(headers["sec-fetch-site"]=="cross-site")return respond(socket,403,"来源不匹配")
        if(request[0]=="GET"&&route=="/"){
            val folder=runCatching{resolve(params["path"].orEmpty())}.getOrNull()?:return respond(socket,400,"目录路径无效")
            if(!folder.isDirectory)return respond(socket,404,"目录不存在")
            return respond(socket,200,page(folder),"text/html; charset=utf-8")
        }
        if((request[0]=="GET"||request[0]=="HEAD")&&route=="/download"){
            val file=runCatching{resolve(params["path"].orEmpty())}.getOrNull()?:return respond(socket,400,"文件路径无效")
            if(!file.isFile||!file.canRead())return respond(socket,404,"文件不存在")
            return streamFile(socket,file,request[0]=="HEAD")
        }
        if(request[0]=="POST"&&route=="/upload"){
            val relative=params["path"].orEmpty()
            val folder=runCatching{resolve(relative)}.getOrNull()?:return respond(socket,400,"目录路径无效")
            if(!folder.isDirectory||!folder.canWrite())return respond(socket,404,"目标目录不可写")
            val name=params["name"]?.takeIf{it.isNotBlank()&&it!="."&&it!=".."&&it.none{c->c=='/'||c=='\\'||c.isISOControl()}}?:return respond(socket,400,"文件名无效")
            val length=headers["content-length"]?.toLongOrNull()?.takeIf{it>0&&it<=folder.usableSpace}?:return respond(socket,413,"文件为空或超过电视当前剩余存储空间")
            val destination=java.io.File(folder,name).canonicalFile
            if(destination.parentFile!=folder.canonicalFile)return respond(socket,400,"文件名无效")
            val temp=java.io.File(folder,".hongguotv-upload-${java.util.UUID.randomUUID()}.uploading")
            return try{
                temp.outputStream().buffered().use{out->
                    val buffer=ByteArray(64*1024);var remaining=length
                    while(remaining>0){
                        if(stopped.get()||System.nanoTime()>=deadline)throw java.io.IOException("传输会话已结束")
                        val count=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
                        if(count<0)throw java.io.EOFException()
                        out.write(buffer,0,count);remaining-=count
                    }
                }
                runCatching{java.nio.file.Files.move(temp.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)}.getOrElse{java.nio.file.Files.move(temp.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)}
                respond(socket,200,"上传完成：${html(name)}")
            }catch(e:Exception){temp.delete();respond(socket,500,"上传失败：${html(e.message?: "未知错误")}") }
        }
        respond(socket,405,"不支持的操作")
    }
    private fun page(folder:java.io.File):String{
        val relative=folder.relativeTo(root).invariantSeparatorsPath
        val parent=folder.parentFile?.takeIf{it.canonicalFile.path.startsWith(root.path)&&it.canonicalFile!=folder.canonicalFile}
        val entries=folder.listFiles()?.filter{item->!item.name.endsWith(".uploading")&&runCatching{val p=item.canonicalFile.path;p==root.path||p.startsWith(root.path+java.io.File.separator)}.getOrDefault(false)}?.sortedWith(compareBy<java.io.File>({!it.isDirectory},{it.name.lowercase(Locale.ROOT)})).orEmpty()
        val rows=buildString{
            if(parent!=null)append("<a class='entry folder' href='?path=${enc(parent.relativeTo(root).invariantSeparatorsPath)}'>⬆️ 返回上一级</a>")
            entries.forEach{item->
                val rel=if(relative.isBlank())item.name else "$relative/${item.name}"
                if(item.isDirectory)append("<a class='entry folder' href='?path=${enc(rel)}'>📁 ${html(item.name)}/</a>")
                else append("<div class='entry'><span>📄 ${html(item.name)} <small>${size(item.length())}</small></span><a class='download' href='/download?path=${enc(rel)}'>下载</a></div>")
            }
            if(entries.isEmpty())append("<p class='muted'>这个目录还是空的</p>")
        }
        val pathLabel=if(relative.isBlank())root.absolutePath else root.absolutePath+"/"+relative
        return """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>电视文件管理</title><style>body{font:16px sans-serif;background:#10131b;color:#f4f5f8;max-width:680px;margin:20px auto;padding:16px;line-height:1.5}h1{font-size:22px}.muted,small{color:#a8b0c0}.entry{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px;border-bottom:1px solid #303644;overflow-wrap:anywhere}.folder{display:block;color:#f4f5f8;text-decoration:none}.download,button{background:#ff634c;color:white;border:0;border-radius:7px;padding:9px 13px;text-decoration:none;white-space:nowrap}section{background:#1a1e28;border-radius:10px;padding:14px;margin:14px 0}input{max-width:100%;margin:8px 0}button{font:inherit;width:100%;padding:12px}#status{font-size:13px;color:#a8b0c0}</style><body data-path="${html(relative)}"><h1>电视公共文件夹</h1><p class="muted">当前位置：${html(pathLabel)}</p><section><strong>上传文件到当前目录</strong><input id="files" type="file" multiple><button id="upload">上传所选文件</button><p id="status">支持任意文件类型，文件大小不超过电视当前剩余存储空间。</p></section><section><strong>目录内容</strong>$rows</section><p class="muted">仅限当前局域网和本次文件管理会话；关闭电视窗口即失效，最长 5 分钟。</p><script >document.getElementById('upload').onclick=async function(){var files=document.getElementById('files').files,status=document.getElementById('status'),button=this;if(!files.length){status.textContent='请先选择文件';return}button.disabled=true;try{for(var i=0;i<files.length;i++){var f=files[i];status.textContent='正在上传 '+f.name+'（'+(i+1)+'/'+files.length+'）';var url='/upload?path='+encodeURIComponent(document.body.getAttribute('data-path'))+'&name='+encodeURIComponent(f.name);var response=await fetch(url,{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:f});var message=await response.text();if(!response.ok)throw new Error(message)}status.textContent='上传完成，正在刷新目录…';location.reload()}catch(e){status.textContent=e.message||'上传失败';button.disabled=false}};</script></html>"""
    }
    companion object { const val DEFAULT_PORT=8765 }
    private fun size(bytes:Long):String=when{bytes<1024->"$bytes B";bytes<1024*1024->"${bytes/1024} KB";bytes<1024L*1024*1024->"%.1f MB".format(Locale.ROOT,bytes/1048576.0);else->"%.2f GB".format(Locale.ROOT,bytes/1073741824.0)}
    private fun streamFile(socket:Socket,file:java.io.File,head:Boolean){
        val name=URLEncoder.encode(file.name,"UTF-8").replace("+","%20")
        val header="HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nContent-Disposition: attachment; filename*=UTF-8''$name\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n"
        socket.getOutputStream().apply{write(header.toByteArray(Charsets.US_ASCII));if(!head)file.inputStream().buffered().use{it.copyTo(this)};flush()}
    }
    private fun respond(socket:Socket,code:Int,message:String,type:String="text/plain; charset=utf-8"){
        val body=message.toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply{write(header.toByteArray(Charsets.US_ASCII));write(body);flush()}
    }
}
