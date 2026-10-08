@extends('portal.layout')
@section('title', 'Employers')
@section('body')
<h1>Employers</h1><p class="sub">Companies this broker places insurance for.</p>
@if($denied)<div class="card empty">Employers are managed by the broker.</div>@else
<div class="card"><h2>Add an employer</h2>
  <form class="form" method="post" action="/app/employers">@csrf
    <label>Code<input name="code" required maxlength="40" placeholder="ACME"></label><label>Legal name<input name="legal_name" required></label><label>Display name<input name="display_name" required></label>
    <div class="actions"><button>Create</button></div></form></div>
<div class="card flush"><div class="table-wrap"><table><thead><tr><th>Code</th><th>Name</th><th>Sector</th><th class="right">Employees</th><th>Parent</th></tr></thead><tbody>
  @forelse(($page['items'] ?? []) as $e)<tr><td class="mono">{{ $e['code'] }}</td><td><strong>{{ $e['display_name'] }}</strong><div class="mut">{{ $e['legal_name'] }}</div></td><td>{{ $e['sector'] }}</td><td class="right num">{{ $e['employee_count'] }}</td><td>{{ $e['parent_employer_id'] }}</td></tr>
  @empty<tr><td colspan="5" class="empty">No employers yet.</td></tr>@endforelse</tbody></table></div></div>
@endif
@endsection
