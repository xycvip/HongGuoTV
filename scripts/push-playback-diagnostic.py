#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
import html,http.client,ipaddress,json,os,re,socket,ssl,sys,urllib.parse,urllib.request
from datetime import datetime,timezone

UA="Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
URLS=re.compile(r"""https?://[^\s<>"'，。]+""",re.I)
FEED_HOSTS=("aweme.snssdk.com","api5-normal-c-hl.amemv.com")

def get(url,headers=None):
 h={"User-Agent":UA,"Accept":"text/html,application/xhtml+xml,application/json,*/*"};h.update(headers or {})
 q=urllib.request.Request(url,headers=h)
 with urllib.request.urlopen(q,timeout=18) as r:return r.status,r.geturl(),r.read(3145728).decode("utf-8","replace")

def public_dns_records(host):
 # 对每个公共 DNS 递归追踪 CNAME 链；每一级分别记录 A/AAAA/CNAME 的 RCODE/Answer。
 # 仅收集公网 IP，避免把 127.0.0.1 等特殊地址用于 HTTPS pinned 连接。
 endpoints=(("Cloudflare DoH","https://cloudflare-dns.com/dns-query?"),
            ("Google DoH","https://dns.google/resolve?"))
 results=[]
 for resolver,base in endpoints:
  queue=[(host,0)];seen=set();ips=[];trace=[]
  while queue and len(seen)<12:
   current,depth=queue.pop(0)
   current=current.rstrip(".").lower()
   if current in seen:continue
   seen.add(current)
   aliases=[]
   for qtype in ("A","AAAA","CNAME"):
    url=base+urllib.parse.urlencode({"name":current,"type":qtype})
    try:
     req=urllib.request.Request(url,headers={"User-Agent":"HongGuoTV-Diagnostic/1.0","Accept":"application/dns-json"})
     with urllib.request.urlopen(req,timeout=8) as r:data=json.loads(r.read(65536).decode("utf-8","replace"))
     status=data.get("Status","未知");answers=[]
     for answer in data.get("Answer",[]):
      typ=answer.get("type");value=str(answer.get("data","")).rstrip(".")
      if typ==1 or typ==28:
       try:
        ip=ipaddress.ip_address(value)
        if ip.is_global:
         ips.append(str(ip));answers.append(str(ip))
        else:answers.append(value+"（非公网）")
       except ValueError:answers.append(value)
      elif typ==5:
       aliases.append(value)
       answers.append("CNAME "+value)
      elif value:answers.append(value)
     trace.append(f"{current} [{qtype} RCODE={status}]: "+(" | ".join(answers[:10]) if answers else "无 Answer"))
    except Exception as e:
     trace.append(f"{current} [{qtype}]: 查询失败 {type(e).__name__}: {e}")
   for alias in aliases:
    if alias.lower().rstrip(".") not in seen and depth<8:
     queue.append((alias,depth+1))
  if queue:trace.append("递归追踪达到安全上限，尚有未查询别名")
  results.append((resolver,list(dict.fromkeys(ips)),"；".join(trace)))
 return results

class PinnedHTTPSConnection(http.client.HTTPSConnection):
 def __init__(self,host,address,timeout=12):
  super().__init__(host,port=443,timeout=timeout,context=ssl.create_default_context())
  self.address=address
 def connect(self):
  raw=socket.create_connection((self.address,self.port),self.timeout)
  self.sock=self._context.wrap_socket(raw,server_hostname=self.host)

def request_feed(host,path,headers):
 # 优先沿用系统 DNS；若解析到回环/非公网地址或请求失败，再用 DoH 公网 IP，
 # 同时保留 HTTPS SNI 和 Host，避免把 IP 当作目标域名请求。
 try:
  infos=socket.getaddrinfo(host,443,type=socket.SOCK_STREAM)
  system_ips=list(dict.fromkeys(x[4][0] for x in infos))
 except Exception:
  system_ips=[]
 usable=[]
 for value in system_ips:
  try:
   ip=ipaddress.ip_address(value)
   if ip.is_global:usable.append(value)
  except ValueError:pass
 errors=[]
 if usable:
  try:
   req=urllib.request.Request("https://"+host+path,headers=headers)
   with urllib.request.urlopen(req,timeout=18) as r:
    return r.status,r.geturl(),r.headers.get("Content-Type","未提供"),r.read(3145728).decode("utf-8","replace"),"系统 DNS"
  except Exception as e:errors.append("系统 DNS 请求失败："+str(e))
 else:
  errors.append("系统 DNS 返回非公网地址："+(", ".join(system_ips) if system_ips else "无可用地址"))
 for resolver,values,dns_detail in public_dns_records(host):
  if not values:errors.append(resolver+"："+dns_detail)
  for address in values:
   try:
    conn=PinnedHTTPSConnection(host,address,timeout=18)
    conn.request("GET",path,headers={**headers,"Host":host})
    response=conn.getresponse()
    body=response.read(3145728).decode("utf-8","replace")
    status=response.status;ctype=response.getheader("Content-Type","未提供")
    conn.close()
    return status,"https://"+host+path,ctype,body,f"{resolver} 公网 IP {address}（绕过异常系统 DNS，保留 TLS SNI/Host）"
   except Exception as e:
    errors.append(f"{resolver} {address} 请求失败："+str(e))
 raise RuntimeError("；".join(errors))

def probe_host(host):
 result={"host":host}
 try:
  infos=socket.getaddrinfo(host,443,type=socket.SOCK_STREAM)
  system_ips=list(dict.fromkeys(x[4][0] for x in infos))
  result["dns"]="成功："+", ".join(system_ips)
 except Exception as e:
  system_ips=[];result["dns"]="失败："+str(e)
 public=public_dns_records(host)
 result["public_dns"]="；".join(label+"："+detail+"；可用公网 IP："+(", ".join(values) if values else "无") for label,values,detail in public)
 candidates=[]
 for value in system_ips:
  try:
   if ipaddress.ip_address(value).is_global:candidates.append(value)
  except ValueError:pass
 for _,values,_ in public:
  candidates.extend(values)
 candidates=list(dict.fromkeys(candidates))
 if not candidates:
  result["tcp"]="未测试（没有可用公网 IP）";result["tls"]="未测试";return result
 result["test_ip"]=candidates[0]
 try:
  with socket.create_connection((candidates[0],443),timeout=6) as raw:
   with ssl.create_default_context().wrap_socket(raw,server_hostname=host) as s:
    result["tcp"]="成功";result["tls"]="成功："+(s.version() or "未知")
 except Exception as e:
  result["tcp"]="失败："+str(e);result["tls"]="失败/未完成"
 return result

def classify(url):
 u=urllib.parse.urlsplit(url);host=(u.hostname or "").lower();p=u.path
 if u.scheme!="https" or u.username or u.port not in (None,443):return
 if host in ("novelquickapp.com","www.novelquickapp.com") and re.fullmatch(r"/s/[A-Za-z0-9_-]+/?",p):return "红果"
 if host in ("hongguoduanju.com","www.hongguoduanju.com") and re.fullmatch(r"/player/\d{1,30}(?:/\d{1,30})?/?",p):return "红果"
 if host=="v.douyin.com" and re.fullmatch(r"/[A-Za-z0-9_-]+/?",p):return "抖音"
 if host in ("www.douyin.com","www.iesdouyin.com") and re.fullmatch(r"/(?:video|share/video)/\d{8,25}/?",p):return "抖音"
 if host=="www.iesdouyin.com" and re.fullmatch(r"/share/playlet/detail/\d{8,25}/?",p):return "抖音短剧"

def clean_title(value):
 value=re.sub(r"https?://\S+"," ",value)
 value=re.sub(r"(复制打开抖音|打开抖音搜索|打开红果短剧|点击链接直接播放).*"," ",value,flags=re.I)
 value=re.sub(r"^\s*(?:分享了|分享给你|作者[:：]?|抖音号[:：]?).*$"," ",value,flags=re.M)
 value=re.sub(r"\s+"," ",value).strip(" \t\r\n，,。.!！；;：:")
 value=re.sub(r"^(?:推荐|分享|短剧|漫剧)[:：]\s*","",value)
 if len(value)>90:value=value[:90].rstrip()
 if not value or re.search(r"(的作品|作品分享|视频作品)$",value):return ""
 return value

def extract_douyin_title(raw,url_start):
 segment=raw[:url_start]
 # “复制打开抖音”可能出现在标题之前，不能直接截断整段分享文案。
 segment=re.sub(r"复制打开抖音[，,]?\s*"," ",segment)
 # 常见单行分享格式：时间/口令 + “看看【作者的作品】” + 剧名/简介 + 链接。
 # 优先截取作者提示之后的正文，避免把前面的“8.79”等分享口令误判成标题。
 m=re.search(r"看看[【〖\[][^】〗\]]{1,60}(?:的作品|作品)[】〗\]]\s*(.*)$",segment)
 if m:
  value=clean_title(m.group(1))
  # 分享文案可能把剧情简介接在标题后，按常见简介引导词截断。
  value=re.split(r"\s+(?:为复活|为救|为了|讲述|本剧|该剧|这部短剧)",value,maxsplit=1)[0].strip()
  return value[:80]
 lines=[x.strip() for x in segment.splitlines() if x.strip()]
 if not lines:return ""
 candidates=[]
 for line in lines[-5:]:
  line=re.sub(r"^[【〖\[][^】〗\]]{1,50}(?:的作品|作品)[】〗\]]\s*","",line)
  line=re.sub(r"^[【〖\[][^】〗\]]{1,50}[】〗\]]\s*","",line)
  line=clean_title(line)
  if line and not re.fullmatch(r"(?:抖音|分享|短剧|漫剧|作品|\d+(?:\.\d+)?)",line):candidates.append(line)
 return candidates[-1] if candidates else ""
def parse(raw):
 out=[];rt=re.findall(r"(?:漫剧|短剧)《([^》]{1,80})》",raw)
 for m in URLS.finditer(raw):
  u=m.group().rstrip("！!。，,；;）)]>""'")
  s=classify(u)
  if not s:continue
  title=""
  if s.startswith("抖音"):title=extract_douyin_title(raw,m.start())
  out.append({"source":s,"url":u,"title":title})
 reds=[x for x in out if x["source"]=="红果"]
 for i,x in enumerate(reds):x["title"]=rt[i] if i<len(rt) else ""
 if not reds:out += [{"source":"红果","url":"","title":t} for t in rt]
 seen=set();return [x for x in out if not ((k:=(x["source"],x["url"],x["title"])) in seen or seen.add(k))]

def nested(o,pred):
 if isinstance(o,dict):
  if pred(o):return o
  for v in o.values():
   z=nested(v,pred)
   if z:return z
 if isinstance(o,list):
  for v in o:
   z=nested(v,pred)
   if z:return z

def inspect_feed_json(body, aid):
 try:
  payload=json.loads(body)
 except Exception as e:
  return None,"",f"JSON 解码失败：{type(e).__name__}: {e}；响应长度 {len(body)} 字符"
 def kind(v):
  if isinstance(v,dict):return "object"
  if isinstance(v,list):return "array"
  if v is None:return "null"
  if isinstance(v,bool):return "boolean"
  if isinstance(v,(int,float)):return "number"
  return "string"
 def keys(v):
  return ",".join(list(v.keys())[:35]) if isinstance(v,dict) else "无"
 summary=[f"JSON 根节点类型：{kind(payload)}",f"JSON 根节点字段：{keys(payload)}"]
 if isinstance(payload,dict):
  for key in ("aweme_list","data","aweme_detail","status","extra","filter_detail"):
   if key in payload:
    val=payload[key]
    summary.append(f"{key} 类型：{kind(val)}"+(f"，数量：{len(val)}" if isinstance(val,(list,dict,str)) else ""))
  for key in ("status_code","code","error_code","message","msg","has_more","cursor"):
   if key in payload and not isinstance(payload[key],(dict,list)):
    val=str(payload[key]).replace("\\n"," ")[:100]
    summary.append(f"顶层 {key}：{val}")
 if not isinstance(payload,(dict,list)):
  summary.append("响应不是对象/数组，无法按 Feed 结构查找作品")
  return None,"","；".join(summary)
 found=None;found_path="";visited=0;id_fields=0;exact_fields=0;sample_paths=[]
 def walk(node,path="$"):
  nonlocal found,found_path,visited,id_fields,exact_fields
  if found is not None:return
  if isinstance(node,dict):
   visited+=1
   for key,val in node.items():
    if key=="aweme_id":
     id_fields+=1
     if str(val)==aid:
      exact_fields+=1;found=node;found_path=path;return
     if len(sample_paths)<5:sample_paths.append(f"{path}.aweme_id({kind(val)})")
   for key in ("status","statistics"):
    val=node.get(key)
    if isinstance(val,dict) and str(val.get("aweme_id",""))==aid:
     exact_fields+=1;found=node;found_path=path;return
   for key,val in node.items():
    if isinstance(val,(dict,list)):walk(val,f"{path}.{key}")
    if found is not None:return
  elif isinstance(node,list):
   for idx,val in enumerate(node):
    if isinstance(val,(dict,list)):walk(val,f"{path}[{idx}]")
    if found is not None:return
 walk(payload)
 summary += [f"遍历对象节点：{visited}",f"发现 aweme_id 字段：{id_fields}",f"精确 ID 匹配：{'是' if found else '否'}"]
 if sample_paths:summary.append("aweme_id 字段示例位置："+", ".join(sample_paths))
 return found,found_path,"；".join(summary)

def scan_feed_titles(body, target_title=""):
 # 不依赖 aweme_id：遍历整个 Feed JSON 的所有字符串叶子节点，
 # 同时收集标题/描述/分享/短剧等字段，并检查分享标题是否出现在任意位置。
 try:
  payload=json.loads(body)
 except Exception:
  return [],[]
 def norm(value):
  return "".join(c for c in str(value).casefold() if c.isalnum())
 wanted=norm(target_title)
 matches=[];candidates=[];visited=0
 def walk(node,path="$",parent=None):
  nonlocal visited
  if isinstance(node,dict):
   visited+=1
   for key,value in node.items():
    child_path=f"{path}.{key}"
    if isinstance(value,str) and value.strip():
     text=value.strip()
     key_hint=any(word in str(key).lower() for word in (
      "title","name","desc","caption","share","series","mix","playlet",
      "album","text","content","abstract","schema"
     ))
     if key_hint and len(text)<=180 and len(candidates)<100:
      candidates.append({"path":child_path,"field":str(key),"value":re.sub(r"\s+"," ",text)[:180]})
     value_norm=norm(text)
     # 空字符串（表情、标点等归一化后为空）绝不能参与匹配；
     # 只允许完整分享剧名出现在较长文本中，不允许短字段反向包含剧名。
     if wanted and len(wanted)>=5 and len(value_norm)>=5 and wanted in value_norm:
      if len(matches)<30:
       matches.append({"path":child_path,"field":str(key),"value":re.sub(r"\s+"," ",text)[:180]})
    elif isinstance(value,(dict,list)):
     walk(value,child_path,str(key))
  elif isinstance(node,list):
   for index,value in enumerate(node):
    if isinstance(value,(dict,list)):
     walk(value,f"{path}[{index}]",parent)
 walk(payload)
 return matches,candidates

def douyin(url, target_title=""):
 u=urllib.parse.urlsplit(url)
 if u.hostname=="v.douyin.com":
  _,final,_=get(url);m=re.search(r"/(?:share/video|video)/(\d{8,25})",final) or re.search(r"[?&]aweme_id=(\d{8,25})",final)
  if not m:raise RuntimeError("短链跳转后没有提取到作品 ID："+final)
  aid=m.group(1)
 else:
  m=re.search(r"/(?:share/video|video)/(\d{8,25})",u.path)
  if not m:raise RuntimeError("作品链接中没有 aweme_id")
  aid=m.group(1)
 attempts=[]
 for host in FEED_HOSTS:
  try:
   feed_path=f"/aweme/v1/feed/?aweme_id={aid}&aid=1128"
   feed_headers={"User-Agent":"com.ss.android.ugc.aweme/390000 (Linux; U; Android 13; zh_CN; Pixel 7; Build/TQ3A.230805.001; Cronet/TTNetVersion)","Accept":"application/json, text/plain, */*","Referer":"https://www.douyin.com/"}
   status,final,content_type,body,route=request_feed(host,feed_path,feed_headers)
   # 在原始响应全文中直接搜索分享剧名片段，不依赖 JSON 字段名或 aweme_id。
   direct_phrase=(target_title or "")[:9]
   direct_hits=[]
   if direct_phrase:
    for label,term in (("原文",direct_phrase),("JSON Unicode 转义", "".join("\\u%04x" % ord(ch) for ch in direct_phrase))):
     pos=body.find(term)
     if pos>=0:
      context=re.sub(r"\s+"," ",body[max(0,pos-100):pos+len(term)+160])
      direct_hits.append(f"{label}命中位置 {pos}，出现 {body.count(term)} 次；上下文：{context}")
   direct_summary="；".join(direct_hits) if direct_hits else f"未找到“{direct_phrase}”原文或 Unicode 转义"
   a,path,diagnostic=inspect_feed_json(body,aid)
   title_matches,title_candidates=scan_feed_titles(body,target_title)
   meta=f"HTTP {status}；Content-Type：{content_type}；最终地址：{final}；请求路径：{route}；响应长度：{len(body)} 字符；原始响应全文直接搜索：{direct_summary}；{diagnostic}"
   if not a and title_matches:
    hit=title_matches[0]
    attempts.append(f"{host}：未命中 aweme_id，但在整个 Feed JSON 的 {hit['path']}（字段 {hit['field']}）发现分享剧名文本：{hit['value']}")
    return aid,target_title,"",attempts
   if not a:
    preview="；".join(f"{x['path']}[{x['field']}]={x['value']}" for x in title_candidates[:12])
    raise RuntimeError(meta+"；全 JSON 标题/描述字段扫描："+(preview or "没有发现可读的标题类字段"))
   si=a.get("series_info") if isinstance(a.get("series_info"),dict) else {}
   mi=a.get("mix_info") if isinstance(a.get("mix_info"),dict) else {}
   next_info=si.get("next_info") if isinstance(si.get("next_info"),dict) else {}
   if not next_info:next_info=mi.get("next_info") if isinstance(mi.get("next_info"),dict) else {}
   share_info=a.get("share_info") if isinstance(a.get("share_info"),dict) else {}
   share_desc=str(share_info.get("share_desc") or "")
   bracket=re.search(r"[【〖]([^】〗]{1,100})[】〗]",share_desc)
   title=(si.get("series_name") or mi.get("mix_name") or next_info.get("name") or (bracket.group(1) if bracket else "")).strip()
   title=re.sub(r"^(?:短剧|漫剧)\s*[·・]\s*","",title).strip()
   if not title and title_matches:
    title=target_title
    hit=title_matches[0]
    attempts.append(f"{host}：目标 aweme_id 已命中；剧名文本另在 {hit['path']}（字段 {hit['field']}）找到：{hit['value']}")
   if not title:
    preview="；".join(f"{x['path']}[{x['field']}]={x['value']}" for x in title_candidates[:12])
    raise RuntimeError(f"HTTP {status}；作品 ID 已在 {path} 匹配，但标准短剧字段没有剧名；全 JSON 标题/描述字段扫描：{preview or '无标题类字段'}；{diagnostic}")
   attempts.append(f"{host}：{diagnostic}")
   return aid,title,(a.get("author") or {}).get("nickname",""),attempts
  except Exception as e:attempts.append(host+"："+str(e))
 return aid,"","",attempts


def douyin_web_detail(aid):
 # 仅做无登录、无签名的公开 Web 详情接口探测；不作为正式解析链路。
 params=urllib.parse.urlencode({"device_platform":"webapp","aid":"6383","channel":"channel_pc_web","aweme_id":aid,"request_source":"600","origin_type":"quick_player"})
 url="https://www.douyin.com/aweme/v1/web/aweme/detail/?"+params
 headers={"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36","Accept":"application/json, text/plain, */*","Referer":"https://www.douyin.com/"}
 status=0;ctype="未提供";final=url;body=""
 try:
  req=urllib.request.Request(url,headers=headers)
  with urllib.request.urlopen(req,timeout=18) as r:
   status=r.status;ctype=r.headers.get("Content-Type","未提供");final=r.geturl();body=r.read(3145728).decode("utf-8","replace")
 except urllib.error.HTTPError as e:
  status=e.code;ctype=e.headers.get("Content-Type","未提供");final=e.geturl();body=e.read(3145728).decode("utf-8","replace")
 meta=f"HTTP {status}；Content-Type：{ctype}；响应长度：{len(body)} 字符"
 if not body.strip():
  return {"meta":meta+"；响应体为空","result":"空响应/可能受风控或签名保护","title":""}
 try:o=json.loads(body)
 except Exception as e:
  challenge=any(x in body.lower() for x in ("captcha","challenge","verify","安全验证"))
  return {"meta":meta+f"；JSON 解码失败：{type(e).__name__}；响应预览："+re.sub(r"\s+"," ",body[:180]),"result":"疑似验证页/非 JSON" if challenge else "非 JSON 响应","title":""}
 if not isinstance(o,(dict,list)):
  return {"meta":meta+"；JSON 根节点不是对象/数组","result":"JSON 结构异常","title":""}
 detail=nested(o,lambda x: str(x.get("aweme_id",""))==aid)
 if detail:
  desc=str(detail.get("desc") or "").strip()
  si=detail.get("series_info") if isinstance(detail.get("series_info"),dict) else {}
  mi=detail.get("mix_info") if isinstance(detail.get("mix_info"),dict) else {}
  title=str(si.get("series_name") or mi.get("mix_name") or "")
  return {"meta":meta+f"；JSON 根节点：{'object' if isinstance(o,dict) else 'array'}；目标 aweme_id 精确匹配：是","result":"成功取得目标作品详情","title":title,"desc":desc}
 keys=",".join(list(o.keys())[:25]) if isinstance(o,dict) else "无"
 code=o.get("status_code",o.get("status", "未提供")) if isinstance(o,dict) else "未提供"
 return {"meta":meta+f"；JSON 根节点：{'object' if isinstance(o,dict) else 'array'}；字段：{keys}；状态码：{code}；目标 aweme_id 精确匹配：否","result":"返回 JSON，但未匹配目标作品","title":""}

def red(url):
 st,final,b=get(url);dec=urllib.parse.unquote(urllib.parse.unquote(final));src=(final,dec,b,html.unescape(b))
 def f(p):
  for s in src:
   m=re.search(p,s,re.I)
   if m:return m.group(1)
 sid=f(r"""["']?(?:video_series_id|series_id|seriesId|series_id_str)["']?\s*[=:]\s*["']?(\d{1,30})""")
 eid=f(r"""["']?(?:share_toast_vid|vid|episode_id|episodeId)["']?\s*[=:]\s*["']?(\d{1,30})""")
 vid=f(r"""["']?video_id["']?\s*[=:]\s*["']?(\d{1,30})""")
 p=re.search(r"/player/(\d{1,30})(?:/(\d{1,30}))?",final)
 if p:sid=sid or p.group(1);eid=eid or p.group(2)
 return sid or vid,eid or (vid if vid!=sid else None)

def search_api(title):
 _,_,b=get("https://hongguoduanju.com/api/search/result/?query="+urllib.parse.quote(title)+"&page=1")
 data=json.loads(b);rows=data.get("searchList")
 if not isinstance(rows,list):raise RuntimeError("搜索响应缺少 searchList")
 out=[]
 for r in rows:
  values=r.get("video_data") or r
  if isinstance(values,dict):values=[values]
  if not isinstance(values,list):continue
  for v in values:
   if not isinstance(v,dict):continue
   # 原接口沿用 App 侧的短剧类型筛选；若类型字段缺失则保留可识别条目。
   genre=str(v.get("genre",""))
   if genre and genre not in ("203","205"):continue
   sid=str(v.get("series_id") or r.get("book_id") or r.get("search_result_id") or v.get("book_id") or "")
   highlight=r.get("search_high_light") or {}
   htitle=highlight.get("title") if isinstance(highlight,dict) else {}
   if isinstance(htitle,dict):htitle=htitle.get("text")
   t=htitle or v.get("title") or v.get("series_name") or r.get("cell_name") or ""
   if sid.isdigit() and t:out.append({"id":sid,"title":str(t),"source":"官方搜索 API"})
 return out

def search(title):
 try:
  rows=search_api(title)
  return rows,[]
 except Exception as e:
  return [],["官方搜索 API："+str(e)]

def detail(sid):
 _,_,b=get("https://hongguoduanju.com/detail?series_id="+sid);mark=b.find("_ROUTER_DATA");start=b.find("{",mark)
 if mark<0 or start<0:raise RuntimeError("详情页没有 _ROUTER_DATA")
 dep=0;quote=esc=False;end=None
 for i,c in enumerate(b[start:],start):
  if quote:
   if esc:esc=False
   elif c=="\\":esc=True
   elif c=='"':quote=False
   continue
  if c=='"':quote=True
  elif c in "{[":dep+=1
  elif c in "}]":
   dep-=1
   if dep==0:end=i+1;break
 if end is None:raise RuntimeError("详情 JSON 不完整")
 o=json.loads(b[start:end]).get("loaderData",{}).get("detail_page",{}).get("seriesDetail",{})
 eps=o.get("vid_list") or []
 if not eps:raise RuntimeError("详情页没有分集列表")
 return o.get("series_name") or o.get("series_title") or "未知剧名",eps

def extract_episode_ids(eps):
 # vid_list 通常是对象数组，不能直接用字符串 eid 判断是否属于 eps。
 ids=[]
 keys=("vid","video_id","episode_id","item_id","id","vid_str","video_id_str","episode_id_str")
 def walk(node):
  if isinstance(node,dict):
   for key,value in node.items():
    normalized=str(key).lower()
    is_id_key=(normalized in keys or
               (normalized.endswith("_id") and any(word in normalized for word in ("vid","video","episode","item"))))
    if is_id_key and value is not None and not isinstance(value,(dict,list)):
     candidate=str(value).strip()
     if candidate.isdigit():ids.append(candidate)
    if isinstance(value,(dict,list)):walk(value)
  elif isinstance(node,list):
   for value in node:walk(value)
  elif isinstance(node,(str,int)) and not isinstance(node,bool):
   # 部分官网 vid_list 直接返回字符串/数字数组，而不是对象数组。
   candidate=str(node).strip()
   if candidate.isdigit():ids.append(candidate)
 walk(eps)
 return list(dict.fromkeys(ids))


def post_hongguo_json(host,path,body):
 # 公开源码中出现的红果 App 路径。此处不伪造 X-Gorgon/X-Khronos；
 # 无签名探测用于判断接口是否仍接受当前请求形态，拒绝/空响应会明确标记。
 query={
  "aid":"8662","app_name":"novelread","version_code":"72932",
  "version_name":"7.2.9.32","device_platform":"android","os":"android",
  "device_type":"Android","device_brand":"Android","os_api":"35",
  "os_version":"15","channel":"googleplay","ac":"wifi","language":"zh",
  "manifest_version_code":"72932","resolution":"1080*1920","dpi":"420",
  "update_version_code":"72932","host_abi":"arm64-v8a",
 }
 url="https://"+host+path+"?"+urllib.parse.urlencode(query)
 data=json.dumps(body,separators=(",",":"),ensure_ascii=False).encode("utf-8")
 headers={
  "User-Agent":"com.phoenix.read/72932 (Linux; U; Android 15; zh_CN; Android; Build/UP1A;tt-ok/3.12.13.20)",
  "Accept":"application/json; charset=utf-8,application/x-protobuf",
  "Content-Type":"application/json; charset=utf-8",
  "Accept-Encoding":"gzip",
  "Connection":"close",
 }
 req=urllib.request.Request(url,data=data,headers=headers,method="POST")
 try:
  with urllib.request.urlopen(req,timeout=15) as r:
   raw=r.read(3145728)
   if r.headers.get("Content-Encoding","").lower()=="gzip":
    import gzip
    raw=gzip.decompress(raw)
   return r.status,r.headers.get("Content-Type","未提供"),raw.decode("utf-8","replace"),url
 except urllib.error.HTTPError as e:
  raw=e.read(3145728)
  if e.headers.get("Content-Encoding","").lower()=="gzip":
   try:
    import gzip
    raw=gzip.decompress(raw)
   except Exception:pass
  return e.code,e.headers.get("Content-Type","未提供"),raw.decode("utf-8","replace"),url

def inspect_red_detail_payload(body,sid,eid):
 if not body.strip():return {"json":False,"title":"","episode_ids":[],"matched":False,"summary":"响应体为空"}
 try:obj=json.loads(body)
 except Exception:return {"json":False,"title":"","episode_ids":[],"matched":False,"summary":"响应不是 JSON"}
 titles=[];vids=[]
 def walk(node):
  if isinstance(node,dict):
   for k,v in node.items():
    if k in ("series_name","series_title","book_name","title") and isinstance(v,str) and v.strip():
     if len(v.strip())<100:titles.append(v.strip())
    if k in ("vid_list","video_list","episodes","episode_list") and isinstance(v,list):
     for item in v:
      if isinstance(item,(str,int)):vids.append(str(item))
      elif isinstance(item,dict):
       for ik in ("vid","video_id","item_id","episode_id","id"):
        if item.get(ik) is not None:vids.append(str(item[ik]))
    if isinstance(v,(dict,list)):walk(v)
  elif isinstance(node,list):
   for x in node:
    if isinstance(x,(dict,list)):walk(x)
 walk(obj)
 vids=list(dict.fromkeys(x for x in vids if x.isdigit()))
 title=next((x for x in titles if x and x not in ("未知剧名",)), "")
 matched=bool(eid and eid in vids)
 return {"json":True,"title":title,"episode_ids":vids,"matched":matched,
         "summary":f"JSON 成功；提取剧名={title or '无'}；分集ID数量={len(vids)}；分享单集ID精确命中={'是' if matched else '否'}"}

def probe_app_detail_endpoint(label,path,body,sid,eid):
 hosts=("api5-normal-sinfonlinea.fqnovel.com","api5-normal-sinfonlineb.fqnovel.com","api5-normal-sinfonlinec.fqnovel.com")
 errors=[]
 for host in hosts:
  try:
   status,ctype,payload,url=post_hongguo_json(host,path,body)
   parsed=inspect_red_detail_payload(payload,sid,eid)
   # 空 body / HTML / 非 JSON 不算接口成功；记录后继续下一 host。
   if parsed["json"] and (parsed["title"] or parsed["episode_ids"]):
    return f"- {label}：HTTP {status}；主机={host}；Content-Type={ctype}；{parsed['summary']}；响应字节={len(payload.encode('utf-8'))}；请求={url}"
   errors.append(f"{host}: HTTP {status}, {len(payload.encode('utf-8'))}字节, {parsed['summary']}")
  except Exception as e:errors.append(f"{host}: {type(e).__name__}: {e}")
 return f"- {label}：未取得可解析详情；逐主机结果："+ "；".join(errors)

def inspect_official_html(label,url,sid,eid):
 try:
  status,final,body=get(url)
  # HTML 中详情数据可能是 RouterData 脚本，也可能直接内嵌 JSON。
  title_match=re.search(r"<title[^>]*>(.*?)</title>",body,re.I|re.S)
  page_title=html.unescape(re.sub(r"\s+"," ",title_match.group(1))).strip() if title_match else ""
  ids=[]
  for key in ("vid_list","video_list"):
   for m in re.finditer(r'"'+key+r'"\s*:\s*\[',body):
    start=m.end()-1;depth=0;quoted=False;escaped=False;end=None
    for j,ch in enumerate(body[start:],start):
     if quoted:
      if escaped:escaped=False
      elif ch=="\\":escaped=True
      elif ch=='"':quoted=False
      continue
     if ch=='"':quoted=True
     elif ch=="[":depth+=1
     elif ch=="]":
      depth-=1
      if depth==0:end=j+1;break
    if end:
     try:
      arr=json.loads(body[start:end])
      for item in arr:
       if isinstance(item,(str,int)):ids.append(str(item))
       elif isinstance(item,dict):
        for k in ("vid","video_id","episode_id","id"):
         if item.get(k) is not None:ids.append(str(item[k]));break
     except Exception:pass
  ids=list(dict.fromkeys(x for x in ids if x.isdigit()))
  title=""
  for pattern in (r'"series_name"\s*:\s*"([^"]+)"',r'"series_title"\s*:\s*"([^"]+)"'):
   m=re.search(pattern,body)
   if m:title=html.unescape(m.group(1));break
  exact=bool(eid and eid in ids)
  return f"- {label}：HTTP {status}；页面标题={page_title[:80] or '无'}；剧名={title or '未解析'}；分集ID={len(ids)}个；分享单集精确命中={'是' if exact else '否'}；最终地址={final}"
 except Exception as e:return f"- {label}：请求失败 {type(e).__name__}: {e}；URL={url}"

def probe_detail_pages(sid,eid):
 # 所有候选分别输出结果；任何单路失败都不能中断其余探测。
 lines=["- 详情接口逐项独立测试："]
 lines.append(inspect_official_html("官网详情查询参数",f"https://hongguoduanju.com/detail?series_id={sid}",sid,eid))
 lines.append(inspect_official_html("官网详情路径",f"https://hongguoduanju.com/detail/{sid}",sid,eid))
 if eid:
  lines.append(inspect_official_html("红果官网单集播放页",f"https://hongguoduanju.com/player/{sid}/{eid}",sid,eid))
  lines.append(inspect_official_html("novelquickapp 单集播放页",f"https://novelquickapp.com/player/{sid}/{eid}",sid,eid))
 detail_body={"biz_param":{"detail_page_version":0,"disable_digg_stat":False,"disable_video_relate_book":False,"image_shrink_datas_str":"W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjkwMCwic2hyaW5rX3R5cGUiOjN9LHsiaW1hZ2VfdHlwZSI6NCwiaW1hZ2Vfd2lkdGgiOjcyLCJzaHJpbmtfdHlwZSI6NH1d\n","need_all_video_definition":False,"need_mp4_align":False,"screen_width_px":"900","source":7,"use_os_player":False,"use_server_dns":False},"series_id":sid}
 preload_body={**detail_body,"dr_scene":"preload"}
 lines.append(probe_app_detail_endpoint("新版单剧详情 video_detail/v1","/novel/player/video_detail/v1/",{"series_id":sid},sid,eid))
 lines.append(probe_app_detail_endpoint("批量分集详情 multi_video_detail/v1","/novel/player/multi_video_detail/v1/",detail_body,sid,eid))
 lines.append(probe_app_detail_endpoint("旧预加载分集详情 multi_video_detail/preload/v1","/novel/player/multi_video_detail/preload/v1",preload_body,sid,eid))
 lines += [
  "- 说明：App 接口探测不伪造 X-Gorgon/X-Khronos；如果服务端要求设备签名，会如实显示拒绝、空响应或非 JSON，不把它记成详情成功。",
  "- 本轮只验证详情和分集 ID，不调用视频模型、不下载视频，也不触发播放器。",
  "- 参考开源实现：danmu_api、zhangbaio/hongguo、gybeyond1/hongguo-dl、zhoufuweigg/guoapp；第三方收费 API 未加入。",
 ]
 return lines

def main():
 raw=os.getenv("PUSH_SHARE_TEXT","")
 if not raw.strip():raise RuntimeError("请在 Actions 输入框粘贴完整分享文案")
 shares=parse(raw)
 L=["# 推送播放模拟诊断报告","",f"- 时间：{datetime.now(timezone.utc).isoformat()}",f"- 输入字符数：{len(raw)}",f"- 识别条数：{len(shares)}","- 诊断任务执行成功仅表示报告已生成，不代表分享解析或播放入口验证成功。","- 本报告请求公开网页/接口，不执行需设备身份签名的 App 接口，也不启动 Android 播放器。",""]
 probes={}
 for host in FEED_HOSTS:probes[host]=probe_host(host)
 L += ["## Feed 网络连通性（独立于接口业务结果）"]
 for host,p in probes.items():L += [f"- {host}：系统 DNS {p.get('dns')}；公共 DNS 对照 {p.get('public_dns','未查询')}；测试 IP {p.get('test_ip','无')}；TCP 443 {p.get('tcp')}；TLS {p.get('tls')}"]
 if not shares:L += ["## 未识别","没有识别到 App 当前支持的红果/抖音分享格式。"]
 for i,s in enumerate(shares[:4],1):
  t=s["title"];direct=False;feed_ok=None
  L += [f"## 分享 {i}：{s['source']}",f"- 初始提取标题：{t or '未提取'}",f"- 链接：{s['url'] or '无'}"]
  try:
   if s["source"]=="抖音":
    aid,feed_title,author,attempts=douyin(s["url"],t)
    L += [f"- aweme_id：{aid}"]
    if feed_title:
     t=feed_title;feed_ok=True
     L += [f"- Feed 确认剧名：**{t}**",f"- 作者：{author or '未返回/未匹配作品对象'}","- Feed 业务解析：成功（可能通过全 JSON 剧名文本匹配）"]
     L += [f"- Feed 额外证据：{x}" for x in attempts]
    else:
     feed_ok=False
     L += ["- Feed 业务解析：失败（已继续执行标题兜底）"]+[f"- Feed 节点 {x}" for x in attempts]
     try:
      wd=douyin_web_detail(aid)
      L += ["- Web 单作品详情诊断："+wd["result"],f"- Web 详情响应：{wd['meta']}"]
      if wd.get("title"):L.append("- Web 详情短剧名称："+wd["title"])
      if wd.get("desc"):L.append("- Web 详情作品描述："+wd["desc"][:160])
     except Exception as e:L += ["- Web 单作品详情诊断：请求异常",f"- 原因：{type(e).__name__}: {e}"]
   elif s["source"]=="抖音短剧":
    pid=re.search(r"/share/playlet/detail/(\d{8,25})",urllib.parse.urlsplit(s["url"]).path).group(1)
    _,_,b=get(f"https://www.iesdouyin.com/web/api/playlet/detail/?playlet_id={pid}");d=json.loads(b).get("data") or {};t=d.get("playlet_name") or d.get("series_name") or ""
    if not t:raise RuntimeError("短剧详情接口未返回剧名")
    L += [f"- playlet_id：{pid}",f"- 接口剧名：**{t}**"]
   elif s["source"]=="红果" and s["url"]:
    sid,eid=red(s["url"]);L += [f"- 红果剧目 ID：{sid or '未提取'}",f"- 红果单集 ID：{eid or '未提取'}"]
    if sid:
     L += probe_detail_pages(sid,eid)
     real,eps=detail(sid)
     t=real
     episode_ids=extract_episode_ids(eps)
     direct=(not eid or eid in episode_ids)
     sample_types=", ".join(type(x).__name__ for x in eps[:5]) or "空列表"
     sample_values=repr(eps[:2])[:360]
     L += ["- 红果官网详情：成功",f"- 剧名：**{real}**",f"- 总集数：{len(eps)}",f"- vid_list 元素类型：{sample_types}",f"- vid_list 原始样例：{sample_values}",f"- 官网分集 ID 提取：{len(episode_ids)} 个；样例：{', '.join(episode_ids[:8]) or '未提取'}"]
     if eid and eid in episode_ids:
      L.append(f"- 分享单集 ID：精确命中第 {episode_ids.index(eid)+1} 集")
     elif eid:
      L.append(f"- 分享单集 ID：未在官网 vid_list 提取的 ID 中命中（{eid}）；剧目详情仍然成功，不中断后续标题搜索")
     else:
      L.append("- 分享单集 ID：未从分享链接提取；剧目详情成功")
  except Exception as e:L += ["- 分享解析/详情：失败",f"- 原因：{e}"]
  if direct:L += ["- 播放入口判断：可定位到红果剧目/分集（未验证设备播放器）",""];continue
  if t.strip():
   try:
    rows,search_errors=search(t)
    norm=lambda x:"".join(c for c in x.lower() if c.isalnum())
    exact=[x for x in rows if norm(x["title"])==norm(t)]
    L += [f"- 红果官方搜索 API：{'成功' if not search_errors else '失败'}，候选 {len(rows)} 个",
          f"- 精确匹配：{len(exact)} 个"]
    L += [f"  - {'【精确】' if norm(x['title'])==norm(t) else '【候选】'} {x['title']}（ID：{x['id']}；来源：{x['source']}）" for x in rows[:10]]
    L += [f"- 搜索异常：{x}" for x in search_errors]
    if len(exact)==1:
     _,eps=detail(exact[0]["id"]);L += [f"- 精确匹配详情：成功，{len(eps)} 集","- 播放入口判断：具备（可进入详情；未实测播放器）"]
    elif rows:L.append("- 播放入口判断：存在候选但未唯一精确匹配，需要进一步确认；未自动播放")
    elif len(search_errors)==2:L.append("- 播放入口判断：两路搜索均异常，无法判断是否存在该剧目")
    else:L.append("- 播放入口判断：两路搜索均未返回候选；这不等于剧目不存在")
   except Exception as e:L += ["- 红果搜索/详情：失败",f"- 原因：{e}"]
  else:
   L.append("- 红果搜索：跳过（分享文案未提供可用剧名；请粘贴 App 的完整分享文案，而不只是短链）")
   L.append("- 播放入口判断：未确认；Feed 失败不等于作品不存在或无法播放")
  if feed_ok is False:L.append("- 诊断结论：Feed 失败与红果搜索结果分开记录，不把连接失败判定为剧目不存在")
  L.append("")
 report="\n".join(L)+"\n";print(report);open("推送播放诊断报告.md","w",encoding="utf-8").write(report)
 if os.getenv("GITHUB_STEP_SUMMARY"):open(os.environ["GITHUB_STEP_SUMMARY"],"a",encoding="utf-8").write(report)
if __name__=="__main__":
 try:main()
 except Exception as e:print("诊断任务失败："+str(e),file=sys.stderr);sys.exit(1)
