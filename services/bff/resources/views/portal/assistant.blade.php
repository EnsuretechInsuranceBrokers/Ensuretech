@extends('portal.layout')
@section('title', 'Assistant')
@section('body')
<h1>Assistant</h1>
<div class="card"><form class="row" method="post" action="/app/assistant">@csrf
  <label style="flex:1">Ask about policies and endorsements<input name="question" value="{{ $question }}" maxlength="1000" required style="width:100%"></label><button>Ask</button></form></div>
@if($answer)<div class="card"><p style="white-space:pre-wrap">{{ $answer['answer'] }}</p>
@foreach(($answer['citations'] ?? []) as $c)<div class="mut">[{{ $c['n'] }}] {{ $c['title'] }}</div>@endforeach</div>@endif
@endsection
