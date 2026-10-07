from django.urls import path

from apps.intake import views as intake
from apps.validation import views as validation

from . import views

urlpatterns = [
    path("", views.JobListView.as_view()),
    path("csv/preview/", intake.CsvPreviewView.as_view()),
    path("csv/upload/", intake.CsvUploadView.as_view()),
    path("csv/mappings/", intake.MappingListCreateView.as_view()),
    path("csv/mappings/<uuid:mapping_id>/", intake.MappingDetailView.as_view()),
    path("<uuid:job_id>/", views.JobDetailView.as_view()),
    path("<uuid:job_id>/audit/", views.JobAuditView.as_view()),
    path("<uuid:job_id>/cancel/", views.JobCancelView.as_view()),
    path("<uuid:job_id>/proceed/", views.JobProceedView.as_view()),
    path("<uuid:job_id>/retry/", views.JobRetryView.as_view()),
    path("<uuid:job_id>/errors/", validation.JobErrorsView.as_view()),
]
