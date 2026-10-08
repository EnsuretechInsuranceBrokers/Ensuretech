<?php

namespace Tests\Feature;

use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class WindowsAndAnnouncementsTest extends TestCase
{
    public function test_an_enrollment_evaluation_is_bound_and_shaped(): void
    {
        $this->fake(['policy.test/*' => Http::response(['status' => 'OPEN', 'startDate' => '2030-03-01', 'endDate' => '2030-04-30', 'confirmedAt' => null, 'windowSet' => true,
            'midtermWindowSet' => true, 'endtermWindowSet' => false, 'customWindowSet' => false, 'closeDate' => null, 'internalTrace' => 'x'])]);
        $r = $this->as()->postJson('/api/v1/broker/policies/7/enrollment-window/evaluate', ['employee_ref' => 'E-1', 'date_of_joining' => '2030-04-02', 'as_of' => '2030-04-10', 'role' => 'x'])->assertOk();
        $r->assertJsonPath('status', 'OPEN')->assertJsonPath('start_date', '2030-03-01')->assertJsonPath('midterm_window_set', true)->assertJsonMissingPath('internalTrace');
        Http::assertSent(fn (Request $q) => str_ends_with($q->url(), '/api/v1/broker/policies/7/enrollment-window/evaluate')
            && $q['employeeRef'] === 'E-1' && $q['dateOfJoining'] === '2030-04-02' && $q['asOf'] === '2030-04-10' && ! isset($q['role']));
    }

    public function test_an_invalid_window_request_never_reaches_the_service(): void
    {
        $this->fake(['policy.test/*' => Http::response([])]);
        $this->as()->putJson('/api/v1/broker/policies/7/enrollment-window', ['enabled' => 'yes please', 'midterm_days' => 9999])->assertStatus(422);
        $this->as()->postJson('/api/v1/broker/policies/7/enrollment-window/evaluate', ['date_of_joining' => 'tomorrow'])->assertStatus(422);
        $this->as()->deleteJson('/api/v1/broker/policies/7/enrollment-window/employees/..%2F..%2Fadmin')->assertStatus(404);
        Http::assertNothingSent();
    }

    public function test_hr_cannot_use_the_window_tools(): void
    {
        $this->fake(['policy.test/*' => Http::response([])]);
        $this->as('EMPLOYER_ADMIN')->getJson('/api/v1/employer/policies/7/enrollment-window')->assertStatus(404);
        $this->as('EMPLOYER_ADMIN')->getJson('/api/v1/broker/policies/7/enrollment-window')->assertStatus(403);
    }

    public function test_employers_read_announcements_through_their_own_gateway_and_see_no_targeting(): void
    {
        $this->fake(['org.test/*' => Http::response([['id' => 1, 'kind' => 'BANNER', 'title' => 'Hello', 'content' => 'c', 'link' => null, 'imageUrl' => null, 'position' => 'TOP', 'sequence' => 0,
            'allEmployers' => true, 'employerIds' => [], 'startsOn' => '2030-01-01', 'ttlDays' => null, 'endsOn' => null, 'active' => true, 'live' => true, 'createdBy' => 'u-1']])]);
        $r = $this->as('EMPLOYER_ADMIN')->getJson('/api/v1/employer/announcements')->assertOk();
        $r->assertJsonPath('0.title', 'Hello')->assertJsonPath('0.all_employers', true)->assertJsonMissingPath('0.createdBy');
        Http::assertSent(fn (Request $q) => str_ends_with($q->url(), '/api/v1/employer/announcements'));
        $this->as('EMPLOYER_ADMIN')->postJson('/api/v1/employer/announcements', ['kind' => 'BANNER', 'title' => 'x', 'content' => 'y'])->assertStatus(405);
    }

    public function test_announcement_links_must_be_http_urls(): void
    {
        $this->fake(['org.test/*' => Http::response([])]);
        $ok = ['kind' => 'NOTICE', 'title' => 't', 'content' => 'c'];
        $this->as()->postJson('/api/v1/broker/announcements', $ok + ['link' => 'javascript:alert(1)'])->assertStatus(422);
        $this->as()->postJson('/api/v1/broker/announcements', array_merge($ok, ['kind' => 'POPUP']))->assertStatus(422);
        Http::assertNothingSent();
    }
}
