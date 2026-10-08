@extends('portal.layout')
@section('title', 'Employers')
@section('body')
<h1>Employers</h1>
@if($denied)<div class="card mut">Employers are managed by the broker.</div>@else
<div class="card"><h2>Add employer</h2>
<form class="row" method="post" action="/app/employers">@csrf
  <label>Code<input name="code" required maxlength="40"></label><label>Legal name<input name="legal_name" required></label><label>Display name<input name="display_name" required></label><button>Create</button>
</form></div>
<div class="card"><table><tr><th>Code</th><th>Name</th><th>Sector</th><th>Employees</th><th>Parent</th></tr>
@forelse(($page['items'] ?? []) as $e)<tr><td>{{ $e['code'] }}</td><td>{{ $e['display_name'] }}</td><td>{{ $e['sector'] }}</td><td>{{ $e['employee_count'] }}</td><td>{{ $e['parent_employer_id'] }}</td></tr>
@empty<tr><td colspan="5" class="mut">No employers.</td></tr>@endforelse</table></div>
@endif
@endsection
