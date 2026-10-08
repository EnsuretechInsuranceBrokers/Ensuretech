<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>@yield('title', 'Ensuretech')</title>
<style nonce="{{ $nonce }}">
:root{--bg:#f6f7f9;--card:#fff;--ink:#1b2430;--mut:#667085;--line:#e4e7ec;--brand:#1f5eff;--ok:#067647;--err:#b42318;--warn:#b54708}
@media(prefers-color-scheme:dark){:root{--bg:#10151c;--card:#171e28;--ink:#e6eaf0;--mut:#98a2b3;--line:#27313f;--brand:#6c93ff;--ok:#47cd89;--err:#f97066;--warn:#fdb022}}
*{box-sizing:border-box}body{margin:0;font:15px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--ink)}
header{background:var(--card);border-bottom:1px solid var(--line);padding:.6rem 1rem;display:flex;gap:1rem;align-items:center;flex-wrap:wrap}
header a{color:var(--ink);text-decoration:none;padding:.2rem .5rem;border-radius:6px}header a:hover{background:var(--bg)}header .sp{flex:1}
main{max-width:1060px;margin:1rem auto;padding:0 1rem}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:1rem;margin-bottom:1rem;overflow-x:auto}
h1{font-size:1.3rem;margin:.2rem 0 1rem}h2{font-size:1.05rem;margin:0 0 .6rem}
table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:.4rem .6rem;border-bottom:1px solid var(--line);white-space:nowrap}th{color:var(--mut);font-weight:600;font-size:.85rem}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:1rem}.n{font-size:1.8rem;font-weight:700}.mut{color:var(--mut)}
input,select,button,textarea{font:inherit;padding:.45rem .6rem;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--ink)}
button{background:var(--brand);color:#fff;border:0;cursor:pointer}button.alt{background:transparent;color:var(--ink);border:1px solid var(--line)}
form.row{display:flex;gap:.5rem;flex-wrap:wrap;align-items:end}form.row label{display:flex;flex-direction:column;font-size:.8rem;color:var(--mut)}
.flash{padding:.6rem .9rem;border-radius:8px;margin-bottom:1rem}.flash.ok{background:color-mix(in srgb,var(--ok) 15%,transparent);color:var(--ok)}.flash.err{background:color-mix(in srgb,var(--err) 15%,transparent);color:var(--err)}
.tag{display:inline-block;padding:.05rem .5rem;border-radius:99px;font-size:.78rem;border:1px solid var(--line)}
.bar{height:8px;background:var(--line);border-radius:99px;overflow:hidden}.bar>i{display:block;height:100%;background:var(--brand);width:0}
a{color:var(--brand)}
</style>
</head>
<body>
@if(isset($user))
<header>
  <strong>Ensuretech</strong>
  <a href="/app">Dashboard</a>
  @if($gw === 'broker')<a href="/app/employers">Employers</a>@endif
  <a href="/app/policies">Policies</a><a href="/app/endorsements">Endorsements</a><a href="/app/cd">CD balance</a><a href="/app/assistant">Assistant</a>
  <span class="sp"></span><span class="mut">{{ $user['name'] ?: $user['email'] }}</span>
  <form method="post" action="/logout">@csrf<button class="alt">Sign out</button></form>
</header>
@endif
<main>
@if(session('ok'))<div class="flash ok">{{ session('ok') }}</div>@endif
@if(session('err'))<div class="flash err">{{ session('err') }}</div>@endif
@if($errors->any())<div class="flash err">{{ $errors->first() }}</div>@endif
@yield('body')
</main>
@stack('scripts')
</body>
</html>
