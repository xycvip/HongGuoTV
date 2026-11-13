#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
import html,json,os,re,socket,ssl,sys,urllib.parse,urllib.request
from datetime import datetime,timezone

UA="Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
URLS=re.compile(r"""https?://[^\s<>"'，。]+""",re.I)
FEED_HOSTS=("aweme.snssdk.com","api5-normal-c-hl.amemv.com")

def get(url,headers=None):
 h={"User-Agent":UA,"Accept":"text/html,application/xhtml+xml,application/json,*/*"};h.update(headers or {})
 q=urllib.request.Request(url,headers=h)
 with urllib.request.urlopen(q,timeout=18) as r:return r.status,r.geturl(),r.read(3145728).decode("utf-8","replace")

def probe_host(host):
 result={"host":host}
 try:
  infos=socket.getaddrinfo(host,443,type=socket.SOCK_STREAM)
  result["dns"]="成功："+", ".join(dict.fromkeys(x[4][0] for x in infos))
 except Exception as e:
  result["dns"]="失败："+str(e);result["tcp"]="未测试";result["tls"]="未测试";return result
 try:
  with socket.create_connection((host,443),timeout=6) as s:result["tcp"]="成功"
 except Exception as e:
  result["tcp"]="失败："+str(e);result["tls"]="未测试";return result
 try:
  ctx=ssl.create_default_context()
  with socket.create_connection((host,443),timeout=6) as raw:
   with ctx.wrap_socket(raw,server_hostname=host) as s:result["tls"]="成功："+(s.version() or "未知")
 except Exception as e:result["tls"]="失败："+str(e)
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
 marker=segment.rfind("复制打开抖音")
 if marker>=0:segment=segment[:marker]
 # 常见单行分享格式：时间/口令 + “看看【作者的作品】” + 剧名/简介 + 链接。
 # 优先截取作者提示之后的正文，避免把前面的“8.79”等分享口令误判成标题。
 m=re.search(r"看看[【〖\\[][^】〗\\]]{1,60}(?:的作品|作品)[】〗\\]]\\s*(.*)$",segment)
 if m:
  value=clean_title(m.group(1))
  # 分享文案可能把剧情简介接在标题后，按常见简介引导词截断。
  value=re.split(r"\\s+(?:为复活|为救|为了|讲述|本剧|该剧|这部短剧)",value,maxsplit=1)[0].strip()
  return value[:80]
 lines=[x.strip() for x in segment.splitlines() if x.strip()]
 if not lines:return ""
 candidates=[]
 for line in lines[-5:]:
  line=re.sub(r"^[【〖\\[][^】〗\\]]{1,50}(?:的作品|作品)[】〗\\]]\\s*","",line)
  line=re.sub(r"^[【〖\\[][^】〗\\]]{1,50}[】〗\\]]\\s*","",line)
  line=clean_title(line)
  if line and not re.fullmatch(r"(?:抖音|分享|短剧|漫剧|作品|\\d+(?:\\.\\d+)?)",line):candidates.append(line)
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

def douyin(url):
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
   _,_,b=get(f"https://{host}/aweme/v1/feed/?aweme_id={aid}&aid=1128",{"User-Agent":"com.ss.android.ugc.aweme/390000 (Linux; U; Android 13; zh_CN; Pixel 7)"})
   a=nested(json.loads(b),lambda x:str(x.get("aweme_id",""))==aid)
   if not a:raise RuntimeError("HTTP 响应成功，但 Feed JSON 未找到对应作品 ID")
   si=a.get("series_info") or {};mi=a.get("mix_info") or {}
   title=(si.get("series_name") or mi.get("mix_name") or "").strip()
   if not title:raise RuntimeError("作品 ID 已匹配，但 Feed 没有短剧名称")
   return aid,title,(a.get("author") or {}).get("nickname",""),attempts
  except Exception as e:attempts.append(host+"："+str(e))
 return aid,"","",attempts

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

def search(title):
 _,_,b=get("https://hongguoduanju.com/api/search/result/?query="+urllib.parse.quote(title)+"&page=1")
 data=json.loads(b);rows=data.get("searchList")
 if not isinstance(rows,list):raise RuntimeError("搜索响应缺少 searchList")
 out=[]
 for r in rows:
  v=r.get("video_data") or r
  if str(v.get("genre","")) not in ("203","205"):continue
  sid=str(v.get("series_id") or r.get("book_id") or r.get("search_result_id") or "")
  t=((r.get("search_high_light") or {}).get("title") or {}).get("text") or v.get("title") or r.get("cell_name") or v.get("series_name") or ""
  if sid.isdigit() and t:out.append((sid,str(t)))
 return out

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

def main():
 raw=os.getenv("PUSH_SHARE_TEXT","")
 if not raw.strip():raise RuntimeError("请在 Actions 输入框粘贴完整分享文案")
 shares=parse(raw)
 L=["# 推送播放模拟诊断报告","",f"- 时间：{datetime.now(timezone.utc).isoformat()}",f"- 输入字符数：{len(raw)}",f"- 识别条数：{len(shares)}","- 诊断任务执行成功仅表示报告已生成，不代表分享解析或播放入口验证成功。","- 本报告请求公开网页/接口，不执行需设备身份签名的 App 接口，也不启动 Android 播放器。",""]
 probes={}
 for host in FEED_HOSTS:probes[host]=probe_host(host)
 L += ["## Feed 网络连通性（独立于接口业务结果）"]
 for host,p in probes.items():L += [f"- {host}：DNS {p.get('dns')}；TCP 443 {p.get('tcp')}；TLS {p.get('tls')}"]
 if not shares:L += ["## 未识别","没有识别到 App 当前支持的红果/抖音分享格式。"]
 for i,s in enumerate(shares[:4],1):
  t=s["title"];direct=False;feed_ok=None
  L += [f"## 分享 {i}：{s['source']}",f"- 初始提取标题：{t or '未提取'}",f"- 链接：{s['url'] or '无'}"]
  try:
   if s["source"]=="抖音":
    aid,feed_title,author,attempts=douyin(s["url"])
    L += [f"- aweme_id：{aid}"]
    if feed_title:
     t=feed_title;feed_ok=True
     L += [f"- Feed 确认剧名：**{t}**",f"- 作者：{author or '未返回'}","- Feed 业务解析：成功"]
    else:
     feed_ok=False
     L += ["- Feed 业务解析：失败（已继续执行标题兜底）"]+[f"- Feed 节点 {x}" for x in attempts]
   elif s["source"]=="抖音短剧":
    pid=re.search(r"/share/playlet/detail/(\d{8,25})",urllib.parse.urlsplit(s["url"]).path).group(1)
    _,_,b=get(f"https://www.iesdouyin.com/web/api/playlet/detail/?playlet_id={pid}");d=json.loads(b).get("data") or {};t=d.get("playlet_name") or d.get("series_name") or ""
    if not t:raise RuntimeError("短剧详情接口未返回剧名")
    L += [f"- playlet_id：{pid}",f"- 接口剧名：**{t}**"]
   elif s["source"]=="红果" and s["url"]:
    sid,eid=red(s["url"]);L += [f"- 红果剧目 ID：{sid or '未提取'}",f"- 红果单集 ID：{eid or '未提取'}"]
    if sid:
     real,eps=detail(sid)
     if eid and eid not in eps:raise RuntimeError("分享集数 ID 不在详情分集列表")
     t=real;direct=True;L += ["- 红果详情：成功",f"- 剧名：**{real}**",f"- 总集数：{len(eps)}",f"- 分享集数：{eps.index(eid)+1 if eid else 1}"]
  except Exception as e:L += ["- 分享解析/详情：失败",f"- 原因：{e}"]
  if direct:L += ["- 播放入口判断：可定位到红果剧目/分集（未验证设备播放器）",""];continue
  if t.strip():
   try:
    rows=search(t);norm=lambda x:"".join(c for c in x.lower() if c.isalnum());exact=[x for x in rows if norm(x[1])==norm(t)]
    L += [f"- 红果搜索：成功，候选 {len(rows)} 个",f"- 精确匹配：{len(exact)} 个"]
    L += [f"  - {'【精确】' if x in exact else '【候选】'} {x[1]}（ID：{x[0]}）" for x in rows[:8]]
    if len(exact)==1:
     _,eps=detail(exact[0][0]);L += [f"- 精确匹配详情：成功，{len(eps)} 集","- 播放入口判断：具备（可进入详情；未实测播放器）"]
    elif rows:L.append("- 播放入口判断：需要用户选择候选，未自动播放")
    else:L.append("- 播放入口判断：未找到剧目")
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
