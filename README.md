# Ensuretech

Our tech stack:  

- Python 3.13+  
- Poetry  
- Django 5.0+  
- Django REST Framework (DRF) (For REST API structure)  
- Celery + RabbitMQ  
- SurrealDB  
- Postgres via Supabase  
- Django ORM  
- NextJS  
- Svelte  
- Docker  
- Kubernetes  
- Strawberry GraphQL  
- drf-spectacular  

## Spring Boot vs Django — Technical Comparison

<table>
<thead>
<tr>
<th>Feature</th>
<th>Spring Boot</th>
<th>Django Advantage</th>
</tr>
</thead>
<tbody>

<tr>
<td><strong>Memory Footprint</strong></td>
<td>JVM baseline starts at ~150 MB minimum; commonly 400 MB+ in production with Tomcat worker threads and heap allocation</td>
<td><strong>Lower Resource Consumption:</strong> Django pods use significantly less RAM, enabling higher replica density on Kubernetes nodes and lower cloud infrastructure costs</td>
</tr>

<tr>
<td><strong>Development Velocity</strong></td>
<td>High boilerplate: separate Controllers, Services, Repositories, and DTOs required per feature</td>
<td><strong>Rapid Prototyping:</strong> "Batteries-included" philosophy with sensible defaults reduces setup time, enabling faster feature delivery and iteration</td>
</tr>

<tr>
<td><strong>ORM Complexity</strong></td>
<td>Hibernate/JPA involves managing entity states, sessions, and lazy loading — a common source of N+1 bugs and complex debugging</td>
<td><strong>Intuitive ORM:</strong> Django's query syntax (<code>Model.objects.filter()</code>) is more readable and Pythonic, reducing the learning curve for database interactions</td>
</tr>

<tr>
<td><strong>Admin Interface</strong></td>
<td>No built-in data management UI; requires a custom-built frontend or heavy third-party tooling</td>
<td><strong>Auto-Generated Admin:</strong> Production-ready UI for managing records, users, and permissions available immediately after <code>python manage.py migrate</code></td>
</tr>

<tr>
<td><strong>Cold Start / HPA</strong></td>
<td>Slow startup driven by class loading, bean initialization, and autoconfiguration. The CPU spike at launch can also be misread by Kubernetes HPA as a traffic surge, triggering unnecessary scale-outs</td>
<td><strong>Faster Scaling:</strong> Quicker boot times mean new pods reach <code>Ready</code> state sooner during autoscaling events, reducing request drop rates under traffic spikes</td>
</tr>

<tr>
<td><strong>Authentication</strong></td>
<td>Spring Security is highly flexible but requires significant XML or Java config to set up OAuth2, JWT, or session management correctly</td>
<td><strong>Built-in Auth System:</strong> CSRF, SQL injection, and XSS protection are enabled by default. A full user model with permissions and session management requires zero configuration</td>
</tr>

<tr>
<td><strong>Data & AI Integration</strong></td>
<td>The ML/AI ecosystem is overwhelmingly Python-native; integrating with Java services requires extra IPC layers (e.g. JPype), adding latency and complexity</td>
<td><strong>Native Python Ecosystem:</strong> PyTorch, Pandas, and the full data science stack import directly into service logic — no inter-process communication overhead</td>
</tr>

<tr>
<td><strong>Migration Management</strong></td>
<td>No built-in migration tool. Spring Boot offers seamless auto-configuration for Flyway and Liquibase — adding the dependency to the classpath is typically sufficient — but it remains an external tool with its own DSL and configuration surface</td>
<td><strong>Built-in Migrations:</strong> Django migration files are Python scripts versioned directly in the repo, making schema history explicit and easy to review in pull requests</td>
</tr>

</tbody>
</table>

These will be integrated in a microservices architecture. We might implement a small section of this in springboot to leverage the jar deployment CI/CD and spring cloud while scaling horizontally.
