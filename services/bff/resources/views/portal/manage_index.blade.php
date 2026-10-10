@extends('portal.layout')
@section('title', 'Settings and tools')
@section('body')
<h1>Settings and tools</h1>
<p class="sub">Everything the system can do that does not have its own page yet. Pick an area, then use the buttons. Each button explains what it does; nothing changes until you press the one that changes something, and anything that removes or closes something asks you to confirm first.</p>
<div class="grid" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(17rem,1fr));gap:1rem">
@foreach($modules as $m)
  <a class="card" href="/app/manage/{{ $m['id'] }}" style="text-decoration:none;color:inherit"><h2 style="margin-top:0">{{ $m['title'] }}</h2><p class="mut">{{ $m['blurb'] }}</p><p class="mut" style="margin-bottom:0">{{ $m['count'] }} {{ $m['count'] === 1 ? 'action' : 'actions' }}</p></a>
@endforeach
</div>
<div class="card flush"><h2>Where the main jobs live</h2><div class="table-wrap"><table><thead><tr><th>Page</th><th>What you can do there</th></tr></thead><tbody>
@foreach($pages as $url => $labels)
  <tr><td><a href="{{ $url }}">{{ $url === '/login' ? 'Sign in' : $url }}</a></td><td>{{ implode(' · ', array_keys($labels)) }}</td></tr>
@endforeach
</tbody></table></div></div>
@endsection
