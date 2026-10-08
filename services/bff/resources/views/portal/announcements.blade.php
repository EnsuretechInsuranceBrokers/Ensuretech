@extends('portal.layout')
@section('title', 'Announcements')
@section('body')
<h1>Announcements</h1><p class="sub">{{ $gw === 'broker' ? 'Messages shown on your employers\' dashboards.' : 'Messages from your broker.' }}</p>
@if($gw === 'broker')
<div class="card"><h2>New announcement</h2>
  <form class="form" method="post" action="/app/announcements">@csrf
    <label>Kind<select name="kind"><option>BANNER</option><option>NOTICE</option><option>CAROUSEL</option></select></label>
    <label class="wide">Title<input name="title" required maxlength="200"></label>
    <label class="wide">Message<textarea name="content" required maxlength="5000"></textarea></label>
    <label>Link (optional)<input name="link" type="url" placeholder="https://"></label><label>Starts<input type="date" name="starts_on"></label>
    <label>Shown for (days)<input type="number" name="ttl_days" min="1" max="3650" placeholder="until retired"></label>
    <label>Order<input type="number" name="sequence" min="0" value="0"></label>
    <label>To<select name="audience"><option value="all">All employers</option>@foreach($employers as $e)<option value="{{ $e['id'] }}">{{ $e['display_name'] }} only</option>@endforeach</select></label>
    <div class="actions"><button>Publish</button></div></form></div>
@endif
<div class="card flush"><div class="table-wrap"><table><thead><tr><th>Title</th><th>Kind</th><th>Shown</th><th>Status</th>@if($gw === 'broker')<th></th>@endif</tr></thead><tbody>
  @forelse($items as $a)<tr><td><strong>{{ $a['title'] }}</strong><div class="mut">{{ \Illuminate\Support\Str::limit($a['content'], 90) }}</div></td><td>{{ $a['kind'] }}</td>
    <td class="nowrap">{{ $a['starts_on'] }}{{ $a['ends_on'] ? ' → '.$a['ends_on'] : ' → until retired' }}<div class="mut">{{ $a['all_employers'] ? 'all employers' : count($a['employer_ids'] ?? []).' employer(s)' }}</div></td>
    <td>@include('portal.pill', ['s' => $a['live'] ? 'ACTIVE' : ($a['active'] ? 'PENDING' : 'INACTIVE')])</td>
    @if($gw === 'broker')<td class="right">@if($a['active'])<form method="post" action="/app/announcements/{{ $a['id'] }}/retire">@csrf<button class="alt sm">Retire</button></form>@endif</td>@endif</tr>
  @empty<tr><td colspan="5" class="empty">Nothing here yet.</td></tr>@endforelse</tbody></table></div></div>
@endsection
