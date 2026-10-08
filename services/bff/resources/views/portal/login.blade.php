@extends('portal.layout')
@section('title', 'Sign in')
@section('body')
<div class="card" style="max-width:380px;margin:4rem auto">
  <h1>Sign in</h1>
  <form method="post" action="/login" style="display:grid;gap:.7rem">@csrf
    <input name="email" type="email" placeholder="Email" value="{{ old('email') }}" required autofocus autocomplete="username">
    <input name="password" type="password" placeholder="Password" required autocomplete="current-password">
    <button>Sign in</button>
  </form>
</div>
@endsection
