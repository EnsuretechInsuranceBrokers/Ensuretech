<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>@yield('title', 'Ensuretech') · Ensuretech</title>
<link rel="stylesheet" href="/portal.css">
</head>
<body>
@isset($user)
<div class="shell">
  <aside class="side">
    <div class="brand"><span class="logo">E</span> Ensuretech</div>
    @php $nav = [['/app', 'Dashboard', 'app'], ['/app/announcements', 'Announcements', 'app/announcements*'], ['/app/policies', 'Policies', 'app/policies*'], ['/app/endorse', 'Add or remove people', ['app/endorse', 'app/endorse/*']], ['/app/endorsements', 'Endorsements', 'app/endorsements*'], ['/app/cd', 'CD balance', 'app/cd*'], ['/app/assistant', 'Assistant', 'app/assistant*']];
       if ($gw === 'broker') array_splice($nav, 1, 0, [['/app/employers', 'Employers', 'app/employers*']]);
       if ($gw === 'broker') $nav[] = ['/app/member-ids', 'TPA member ids', 'app/member-ids*'];
       if ($gw === 'broker' && config('bff.features.qa_tools')) $nav[] = ['/app/qa/timing', 'QA timing', 'app/qa/*'];
       if ($gw === 'tpa') $nav = [['/app/member-ids', 'Member ids and e-cards', 'app/member-ids*']]; @endphp
    <nav class="nav">
      @foreach($nav as [$href, $label, $pattern])
        <a href="{{ $href }}" @if(request()->is($pattern)) aria-current="page" @endif><span class="dot"></span>{{ $label }}</a>
      @endforeach
    </nav>
    <div class="foot">
      <div class="who">{{ $user['name'] ?: $user['email'] }}</div>
      <div class="role">{{ ucwords(strtolower(str_replace('_', ' ', $user['role']))) }}</div>
      <form method="post" action="/logout" style="margin-top:.6rem">@csrf<button class="alt sm">Sign out</button></form>
    </div>
  </aside>
  <main class="main">
@else
  <main>
@endisset
    @isset($user)
    @if(session('ok'))<div class="flash ok" role="status">{{ session('ok') }}</div>@endif
    @if(session('err'))<div class="flash err" role="alert">{{ session('err') }}</div>@endif
    @if($errors->any())<div class="flash err" role="alert">{{ $errors->first() }}</div>@endif
    @endisset
    @yield('body')
  </main>
@isset($user)
</div>
@endisset
@stack('scripts')
</body>
</html>
