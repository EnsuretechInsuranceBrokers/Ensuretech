<?php

namespace Tests\Feature;

use Illuminate\Http\Client\Request;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class UploadAndStreamTest extends TestCase
{
    private const JOB = ['id' => 'a1b2c3d4-0000-4000-8000-000000000001', 'policy_id' => 100, 'employer_id' => 10, 'broker_id' => 1, 'op_type' => 'ADD', 'status' => 'REQUEST_RAISED',
        'progress_url' => 'http://engine.internal/api/v1/broker/endorsements/a1b2c3d4-0000-4000-8000-000000000001/progress/'];

    public function test_a_csv_upload_is_streamed_through_with_only_its_declared_fields(): void
    {
        $this->fake(['engine.test/*' => Http::response(self::JOB, 202)]);
        $file = UploadedFile::fake()->createWithContent('../../etc/pass wd.csv', "Emp Code,Relationship\n1,Self\n");
        $this->as()->post('/api/v1/broker/endorsements/csv/upload', ['file' => $file, 'policy_id' => 100, 'op_type' => 'ADD', 'effective_date' => '2030-04-01',
            'broker_id' => 99, 'is_admin' => 'yes'], ['Idempotency-Key' => 'idem-1', 'Accept' => 'application/json'])
            ->assertStatus(202)->assertJsonPath('status', 'REQUEST_RAISED')
            ->assertJsonPath('progress_url', '/api/v1/broker/endorsements/a1b2c3d4-0000-4000-8000-000000000001/progress');      // the BFF's own URL, not the engine's
        Http::assertSent(function (Request $r) {
            $names = array_column($r->data(), 'name');
            $file = collect($r->data())->firstWhere('name', 'file');

            return str_starts_with($r->url(), 'http://engine.test/api/v1/broker/endorsements/csv/upload/') && $r->header('Idempotency-Key') === ['idem-1']
                && $names === ['policy_id', 'op_type', 'effective_date', 'file'] && $file['filename'] === 'pass wd.csv'
                && str_contains((string) $file['contents'], 'Emp Code');
        });
        $this->assertArrayNotHasKey('broker_id', $this->as()->getJson('/api/v1/broker/endorsements/a1b2c3d4-0000-4000-8000-000000000001')->json() ?: []);
    }

    public function test_an_upload_without_a_file_or_with_a_huge_one_never_reaches_the_engine(): void
    {
        $this->fake();
        $this->as()->post('/api/v1/broker/endorsements/csv/upload', ['policy_id' => 1], ['Accept' => 'application/json'])->assertStatus(422)->assertJsonPath('errors.file.0', 'a file is required');
        config(['bff.limits.max_upload_bytes' => 10]);
        $this->as()->post('/api/v1/broker/endorsements/csv/upload', ['file' => UploadedFile::fake()->createWithContent('a.csv', str_repeat('x', 100)), 'policy_id' => 1],
            ['Accept' => 'application/json'])->assertStatus(413);
        Http::assertNothingSent();
    }

    public function test_uploads_are_rate_limited_harder_than_reads(): void
    {
        $this->fake(['engine.test/*' => Http::response(self::JOB, 202)]);
        config(['bff.rate_limits.heavy_per_minute' => 2]);
        $this->app['cache']->flush();
        $send = fn () => $this->as()->post('/api/v1/broker/endorsements/csv/upload', ['file' => UploadedFile::fake()->createWithContent('a.csv', 'x'), 'policy_id' => 1], ['Accept' => 'application/json']);
        $send()->assertStatus(202);
        $send()->assertStatus(202);
        $send()->assertStatus(429)->assertJsonPath('title', 'Too many requests')->assertHeader('Retry-After');
    }

    public function test_progress_events_are_reshaped_event_by_event(): void
    {
        $sse = "event: progress\ndata: ".json_encode(['job_id' => 'j', 'state' => 'VALIDATION_IN_PROGRESS', 'status_code' => 210, 'phase' => 'VALIDATION', 'category' => 'PROCESSING',
            'percent' => 10, 'total_rows' => 5, 'failed_rows' => 0, 'chunk_count' => 1, 'chunks_validated' => 0, 'chunks_priced' => 0, 'chunks_endorsed' => 0, 'broker_id' => 1])."\n\n"
            .": keep-alive\n\n"
            ."event: <script>\ndata: not json\n\n";
        $this->fake(['engine.test/*' => Http::response($sse, 200, ['Content-Type' => 'text/event-stream'])]);
        $r = $this->as()->get('/api/v1/broker/endorsements/a1b2c3d4-0000-4000-8000-000000000001/progress');
        $r->assertOk()->assertHeader('Content-Type', 'text/event-stream; charset=utf-8');
        $out = $r->streamedContent();
        $this->assertStringContainsString('event: progress', $out);
        $this->assertStringContainsString('"status":"VALIDATION_IN_PROGRESS"', $out);
        $this->assertStringContainsString(': keep-alive', $out);
        $this->assertStringNotContainsString('status_code', $out);
        $this->assertStringNotContainsString('broker_id', $out);
        $this->assertStringNotContainsString('<script>', $out);
        $this->assertStringContainsString('unreadable event', $out);
    }

    public function test_a_refused_stream_is_a_normal_problem(): void
    {
        $this->fake(['engine.test/*' => Http::response(['detail' => 'Not found.'], 404)]);
        $this->as()->get('/api/v1/broker/endorsements/a1b2c3d4-0000-4000-8000-000000000001/progress', ['Accept' => 'application/json'])->assertStatus(404);
    }
}
