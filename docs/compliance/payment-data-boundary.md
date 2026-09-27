# Payment Data Boundary

This project must keep cardholder data outside the application unless a separate compliance architecture explicitly approves handling it.

Prefer provider-hosted/tokenized payment collection.

Before any real-money deployment, perform:
- PCI DSS scope assessment;
- applicable KYC/AML/legal review;
- privacy/data-protection review;
- payment-provider contract and webhook verification review;
- retention/deletion policy review.

This repository is an engineering project specification, not a legal/compliance certification.
